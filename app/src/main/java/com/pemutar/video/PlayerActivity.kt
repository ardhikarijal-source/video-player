package com.pemutar.video

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.pemutar.video.databinding.ActivityPlayerBinding
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Layar pemutar video.
 *
 * Gestur:
 *  - ketuk sekali          : tampil/sembunyikan kontrol
 *  - ketuk dua kali        : kiri = mundur 10 dtk, kanan = maju 10 dtk, tengah = putar/jeda
 *  - geser vertikal kiri   : kecerahan
 *  - geser vertikal kanan  : volume
 *  - geser horizontal      : maju/mundur (seek)
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_TITLES = "extra_titles"
        const val EXTRA_INDEX = "extra_index"
    }

    private enum class Gesture { NONE, SEEK, BRIGHTNESS, VOLUME }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var locked = false
    private var resizeIndex = 0
    private var orientationMode = 0 // 0 = otomatis (ikuti video), 1 = landscape, 2 = portrait
    private var currentMediaId: String? = null

    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var touchSlop = 0
    private var startPos = 0L
    private var seekTarget = 0L
    private var startVolume = 0f
    private var startBrightness = 0.5f

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeNames = arrayOf("Pas layar", "Regangkan", "Zoom (potong tepi)")

    private val hideHudRunnable = Runnable { binding.tvHud.visibility = View.GONE }
    private val hideUnlockRunnable = Runnable { binding.btnUnlock.visibility = View.GONE }

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addSubtitle(uri)
        }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (orientationMode == 0) applyAutoOrientation(videoSize)
            updatePipParams()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                currentMediaId?.let { Prefs.setPosition(this@PlayerActivity, it, 0L) }
            }
            currentMediaId = mediaItem?.mediaId
            binding.tvTitle.text = mediaItem?.mediaMetadata?.title ?: ""
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                currentMediaId?.let { Prefs.setPosition(this@PlayerActivity, it, 0L) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.err_play, error.errorCodeName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        setupWindow()
        setupPlayer()
        setupControls()
        setupGestures()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) showUnlockTemporarily() else finish()
            }
        })

        loadFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePosition()
        loadFromIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Tekan Home saat video berjalan: lanjut di jendela mengambang
        if (player?.isPlaying == true && !locked) tryEnterPip(false)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) binding.topBar.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- setup

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(i.left + dp(8), i.top + dp(4), i.right + dp(8), dp(4))
            insets
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupPlayer() {
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
        exo.addListener(playerListener)
        player = exo

        binding.playerView.player = exo
        binding.playerView.setShowSubtitleButton(true)
        binding.playerView.setRepeatToggleModes(
            RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
        )
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.topBar.visibility = if (locked) View.GONE else visibility
            }
        )
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSubtitle.setOnClickListener { subtitlePicker.launch(arrayOf("*/*")) }
        binding.btnAspect.setOnClickListener { cycleResize() }
        binding.btnRotate.setOnClickListener { cycleOrientation() }
        binding.btnPip.setOnClickListener { tryEnterPip(true) }
        binding.btnLock.setOnClickListener { setLocked(true) }
        binding.btnUnlock.setOnClickListener { setLocked(false) }

        val pipSupported = Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        binding.btnPip.visibility = if (pipSupported) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) showUnlockTemporarily() else toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = binding.playerView.width
                when {
                    e.x < w / 3f -> seekRelative(-10_000L)
                    e.x > w * 2f / 3f -> seekRelative(10_000L)
                    else -> togglePlay()
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { v, ev -> handleTouch(v, ev) }
    }

    // ---------------------------------------------------------------- memuat media

    private fun loadFromIntent(intent: Intent) {
        val p = player ?: return
        val uris = intent.getStringArrayListExtra(EXTRA_URIS)
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES)

        val items = ArrayList<MediaItem>()
        var startIndex = 0

        if (uris != null && uris.isNotEmpty()) {
            uris.forEachIndexed { i, s ->
                items.add(buildItem(Uri.parse(s), titles?.getOrNull(i)))
            }
            startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1)
        } else {
            val data = intent.data
            if (data == null) {
                Toast.makeText(this, R.string.no_video_data, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            items.add(buildItem(data, null))
        }

        val savedPos = Prefs.getPosition(this, items[startIndex].mediaId)
        p.setMediaItems(items, startIndex, if (savedPos > 0L) savedPos else C.TIME_UNSET)
        p.prepare()
        p.playWhenReady = true
    }

    private fun buildItem(uri: Uri, title: String?): MediaItem {
        val name = title ?: queryName(uri)
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
    }

    private fun queryName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val n = c.getString(idx)
                            if (!n.isNullOrEmpty()) return n
                        }
                    }
                }
            } catch (e: Exception) {
                // pakai nama dari path
            }
        }
        val last = uri.lastPathSegment
        return if (last.isNullOrEmpty()) "Video" else last.substringAfterLast('/')
    }

    // ---------------------------------------------------------------- subtitle

    private fun addSubtitle(uri: Uri) {
        val p = player ?: return
        val current = p.currentMediaItem ?: return
        val fileName = queryName(uri)
        val lower = fileName.lowercase()

        val mime = when {
            lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            lower.endsWith(".ttml") || lower.endsWith(".xml") || lower.endsWith(".dfxp") ->
                MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        val sub = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("und")
            .setLabel(fileName)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        val existing = current.localConfiguration?.subtitleConfigurations ?: emptyList()
        val newItem = current.buildUpon()
            .setSubtitleConfigurations(existing + sub)
            .build()

        val index = p.currentMediaItemIndex
        val pos = p.currentPosition
        p.replaceMediaItem(index, newItem)
        p.seekTo(index, pos)
        Toast.makeText(this, R.string.subtitle_loaded, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- tombol atas

    private fun cycleResize() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        binding.playerView.resizeMode = resizeModes[resizeIndex]
        showHud(resizeNames[resizeIndex])
    }

    private fun cycleOrientation() {
        orientationMode = (orientationMode + 1) % 3
        when (orientationMode) {
            0 -> {
                player?.videoSize?.let { applyAutoOrientation(it) }
                showHud("Rotasi: otomatis")
            }
            1 -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                showHud("Rotasi: landscape")
            }
            else -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                showHud("Rotasi: portrait")
            }
        }
    }

    private fun applyAutoOrientation(size: VideoSize) {
        if (size.width == 0 || size.height == 0) return
        val rotated = size.unappliedRotationDegrees % 180 != 0
        val w = if (rotated) size.height else size.width
        val h = if (rotated) size.width else size.height
        requestedOrientation = if (w >= h) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        binding.playerView.controllerAutoShow = !lock
        if (lock) {
            binding.playerView.hideController()
            binding.topBar.visibility = View.GONE
            showUnlockTemporarily()
        } else {
            handler.removeCallbacks(hideUnlockRunnable)
            binding.btnUnlock.visibility = View.GONE
            binding.playerView.showController()
        }
    }

    private fun showUnlockTemporarily() {
        binding.btnUnlock.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlockRunnable)
        handler.postDelayed(hideUnlockRunnable, 2500)
    }

    // ---------------------------------------------------------------- PiP

    private fun tryEnterPip(showError: Boolean) {
        if (Build.VERSION.SDK_INT < 26 ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            if (showError) Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        try {
            enterPictureInPictureMode(buildPipParams())
        } catch (e: Exception) {
            if (showError) Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val vs = player?.videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) {
            val ratio = vs.width.toFloat() / vs.height.toFloat()
            if (ratio in 0.42f..2.39f) builder.setAspectRatio(Rational(vs.width, vs.height))
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                setPictureInPictureParams(buildPipParams())
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    // ---------------------------------------------------------------- gestur

    private fun handleTouch(v: View, ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        if (locked) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                gesture = Gesture.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) return true
                val dx = ev.x - downX
                val dy = ev.y - downY

                if (gesture == Gesture.NONE &&
                    (abs(dx) > touchSlop * 2 || abs(dy) > touchSlop * 2)
                ) {
                    gesture = when {
                        abs(dx) > abs(dy) -> Gesture.SEEK
                        downX < v.width / 2f -> Gesture.BRIGHTNESS
                        else -> Gesture.VOLUME
                    }
                    startGesture()
                }

                when (gesture) {
                    Gesture.SEEK -> updateSeek(dx, v.width)
                    Gesture.BRIGHTNESS -> updateBrightness(dy, v.height)
                    Gesture.VOLUME -> updateVolume(dy, v.height)
                    Gesture.NONE -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return true
    }

    private fun startGesture() {
        val p = player ?: return
        when (gesture) {
            Gesture.SEEK -> {
                startPos = p.currentPosition
                seekTarget = startPos
            }
            Gesture.BRIGHTNESS -> startBrightness = currentBrightness()
            Gesture.VOLUME ->
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            Gesture.NONE -> {}
        }
    }

    private fun updateSeek(dx: Float, width: Int) {
        val p = player ?: return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0L || !p.isCurrentMediaItemSeekable) return
        if (width <= 0) return

        // Geser selebar layar = 90 detik
        val delta = (dx / width * 90_000f).toLong()
        seekTarget = (startPos + delta).coerceIn(0L, duration)
        val diff = seekTarget - startPos
        val sign = if (diff >= 0) "+" else "-"
        showHud("$sign${Fmt.duration(abs(diff))}  (${Fmt.duration(seekTarget)})")
    }

    private fun updateBrightness(dy: Float, height: Int) {
        if (height <= 0) return
        val value = (startBrightness - dy / height * 1.3f).coerceIn(0.01f, 1f)
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
        showHud("Kecerahan ${(value * 100).roundToInt()}%")
    }

    private fun updateVolume(dy: Float, height: Int) {
        if (height <= 0) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val value = (startVolume - dy / height * max * 1.3f).coerceIn(0f, max.toFloat())
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.roundToInt(), 0)
        showHud("Volume ${(value / max * 100).roundToInt()}%")
    }

    private fun endGesture() {
        if (gesture == Gesture.SEEK) {
            player?.seekTo(seekTarget)
        }
        gesture = Gesture.NONE
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    // ---------------------------------------------------------------- aksi dasar

    private fun toggleControls() {
        if (binding.playerView.isControllerFullyVisible) {
            binding.playerView.hideController()
        } else {
            binding.playerView.showController()
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
            p.play()
        }
    }

    private fun seekRelative(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration
        var target = p.currentPosition + deltaMs
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
        p.seekTo(target.coerceAtLeast(0L))
        showHud(if (deltaMs > 0) "+10 detik" else "-10 detik")
    }

    private fun showHud(text: String) {
        binding.tvHud.text = text
        binding.tvHud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, 800)
    }

    private fun savePosition() {
        val p = player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        if (id.isEmpty() || p.isCurrentMediaItemLive) return
        val duration = p.duration
        val pos = p.currentPosition
        val nearEdge = duration != C.TIME_UNSET && duration > 0 &&
            (pos < 5_000L || pos > duration - 10_000L)
        Prefs.setPosition(this, id, if (nearEdge) 0L else pos)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
