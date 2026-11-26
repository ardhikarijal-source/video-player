package com.pemutar.video

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.os.Environment
import android.content.ContentValues
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.pemutar.video.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var allVideos: List<VideoItem> = emptyList()
    private var loaded = false
    private var currentFolderId: String? = null
    private var currentFolderName: String = ""
    private var query = ""
    private var tabIndex = 0
    private var askedPermission = false

    // Auto update
    private var updateChecked = false
    private var askedNotifPermission = false
    private var lastRelease: Updater.Release? = null
    private var pendingCopy: VideoItem? = null
    private val selectedVideoIds = linkedSetOf<String>()
    private val selectedFolderIds = linkedSetOf<String>()
    private var selectionMode = false

    private val videoAdapter = VideoAdapter(
        { video -> if (selectionMode) toggleVideoSelection(video) else playVideo(video) },
        { video -> toggleVideoSelection(video) }
    )
    private val folderAdapter = FolderAdapter(
        { folder -> if (selectionMode) toggleFolderSelection(folder) else openFolder(folder) },
        { folder -> toggleFolderSelection(folder) }
    )

    private val gridManager by lazy { GridLayoutManager(this, 2) }
    private val listManager by lazy { LinearLayoutManager(this) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            askedPermission = true
            if (!granted) refreshList()
            maybeCheckUpdate()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) lastRelease?.let { Updater.notifyUpdate(this, it) }
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startPlayerWithUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item -> handleMenu(item.itemId) }
        binding.toolbar.setNavigationOnClickListener { if (selectionMode) clearSelection() else exitFolder() }
        setupSearch()

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabIndex = tab?.position ?: 0
                currentFolderId = null
                refreshList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnGrant.setOnClickListener { requestPermissionOrSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionMode) clearSelection() else if (currentFolderId != null) exitFolder() else finish()
            }
        })

        if (!hasPermission()) {
            askedPermission = true
            permissionLauncher.launch(requiredPermission())
        } else {
            maybeCheckUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) loadVideos() else refreshList()

    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---------- Izin ----------

    private fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionOrSettings() {
        val perm = requiredPermission()
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm) || !askedPermission) {
            permissionLauncher.launch(perm)
        } else {
            // Sudah ditolak permanen: arahkan ke pengaturan aplikasi
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
            startActivity(intent)
        }
    }

    // ---------- Memuat daftar ----------

    private fun loadVideos() {
        if (!loaded) binding.progress.visibility = View.VISIBLE
        executor.execute {
            val result = VideoRepository.load(applicationContext)
            mainHandler.post {
                if (!isDestroyed) {
                    allVideos = result
                    loaded = true
                    binding.progress.visibility = View.GONE
                    refreshList()
                }
            }
        }
    }

    private fun sortVideos(list: List<VideoItem>): List<VideoItem> = when (Prefs.getSort(this)) {
        0 -> list.sortedByDescending { it.dateAdded }
        1 -> list.sortedBy { it.name.lowercase() }
        2 -> list.sortedByDescending { it.durationMs }
        else -> list.sortedByDescending { it.size }
    }

    private fun refreshList() {
        val q = query.trim().lowercase()
        val folderId = currentFolderId

        if (tabIndex == 0 || folderId != null) {
            // Video: grid 2 kolom memanjang ke bawah
            val base = if (folderId != null) allVideos.filter { it.bucketId == folderId } else allVideos
            val filtered = if (q.isEmpty()) base else base.filter { it.name.lowercase().contains(q) }
            val sorted = sortVideos(filtered)
            if (binding.list.layoutManager !== gridManager) binding.list.layoutManager = gridManager
            if (binding.list.adapter !== videoAdapter) binding.list.adapter = videoAdapter
            videoAdapter.submit(sorted)
            videoAdapter.setSelectedIds(selectedVideoUris())
            updateEmpty(sorted.isEmpty())
        } else {
            // Folder: daftar 1 kolom
            val folders = allVideos
                .groupBy { it.bucketId }
                .map { (id, vids) ->
                    FolderItem(id, vids.first().bucketName, vids.size, vids.sumOf { it.size })
                }
                .filter { q.isEmpty() || it.name.lowercase().contains(q) }
                .sortedBy { it.name.lowercase() }
            if (binding.list.layoutManager !== listManager) binding.list.layoutManager = listManager
            if (binding.list.adapter !== folderAdapter) binding.list.adapter = folderAdapter
            folderAdapter.submit(folders)
            folderAdapter.setSelectedIds(selectedFolderIds)
            updateEmpty(folders.isEmpty())
        }

        if (selectionMode) {
            binding.toolbar.title = "${selectedFolderIds.size + selectedVideoIds.size} dipilih"
            binding.toolbar.setNavigationIcon(R.drawable.ic_close)
        } else if (folderId != null) {
            binding.toolbar.title = currentFolderName
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        } else {
            binding.toolbar.title = getString(R.string.app_name)
            binding.toolbar.navigationIcon = null
        }
        updateSelectionMenu()
    }

    private fun selectedVideoUris(): Set<String> {
        val ids = selectedVideoIds.toMutableSet()
        ids.addAll(allVideos.filter { selectedFolderIds.contains(it.bucketId) }.map { it.uri.toString() })
        return ids
    }

    private fun updateSelectionMenu() {
        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_selection_share)?.isVisible = selectionMode
        menu.findItem(R.id.action_selection_delete)?.isVisible = selectionMode
        menu.findItem(R.id.action_search)?.isVisible = !selectionMode
        menu.findItem(R.id.action_sort)?.isVisible = !selectionMode
        menu.findItem(R.id.action_open_file)?.isVisible = !selectionMode
        menu.findItem(R.id.action_open_url)?.isVisible = !selectionMode
        menu.findItem(R.id.action_default)?.isVisible = !selectionMode
        menu.findItem(R.id.action_update)?.isVisible = !selectionMode
        menu.findItem(R.id.action_refresh)?.isVisible = !selectionMode
        menu.findItem(R.id.action_paste)?.isVisible = !selectionMode && pendingCopy != null
    }

    private fun beginSelection() {
        selectionMode = true
        updateSelectionMenu()
        refreshList()
    }

    private fun toggleVideoSelection(video: VideoItem) {
        if (!selectionMode) beginSelection()
        val id = video.uri.toString()
        if (!selectedVideoIds.add(id)) selectedVideoIds.remove(id)
        if (selectedVideoIds.isEmpty() && selectedFolderIds.isEmpty()) selectionMode = false
        refreshList()
    }

    private fun toggleFolderSelection(folder: FolderItem) {
        if (!selectionMode) beginSelection()
        if (!selectedFolderIds.add(folder.bucketId)) selectedFolderIds.remove(folder.bucketId)
        if (selectedVideoIds.isEmpty() && selectedFolderIds.isEmpty()) selectionMode = false
        refreshList()
    }

    private fun clearSelection() {
        selectionMode = false
        selectedVideoIds.clear()
        selectedFolderIds.clear()
        refreshList()
    }

    private fun selectedVideos(): List<VideoItem> {
        val ids = selectedVideoUris()
        return allVideos.filter { ids.contains(it.uri.toString()) }
    }

    private fun shareSelected() {
        val videos = selectedVideos()
        if (videos.isEmpty()) return
        val uris = ArrayList(videos.map { it.uri })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(contentResolver, videos.first().name, videos.first().uri).also { clip ->
                videos.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Bagikan video terpilih"))
    }

    private fun deleteSelected() {
        val videos = selectedVideos()
        if (videos.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Hapus ${videos.size} video?")
            .setMessage("Video yang dipilih akan dihapus dari perangkat.")
            .setPositiveButton("Hapus") { _, _ ->
                var deleted = 0
                var failed = 0
                videos.forEach { video ->
                    try {
                        if (contentResolver.delete(video.uri, null, null) > 0) deleted++ else failed++
                    } catch (_: Exception) { failed++ }
                }
                clearSelection()
                Toast.makeText(this, "$deleted video dihapus${if (failed > 0) ", $failed gagal" else ""}", Toast.LENGTH_LONG).show()
                loadVideos()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun updateEmpty(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty && !binding.progress.isShown) View.VISIBLE else View.GONE
        if (!isEmpty) return
        if (!hasPermission()) {
            binding.tvEmpty.setText(R.string.empty_need_permission)
            binding.btnGrant.visibility = View.VISIBLE
        } else {
            binding.tvEmpty.setText(R.string.empty_no_video)
            binding.btnGrant.visibility = View.GONE
        }
    }

    private fun openFolder(folder: FolderItem) {
        currentFolderId = folder.bucketId
        currentFolderName = folder.name
        refreshList()
    }

    private fun exitFolder() {
        if (currentFolderId != null) {
            currentFolderId = null
            refreshList()
        }
    }

    // ---------- Menu ----------

    private fun setupSearch() {
        val item = binding.toolbar.menu.findItem(R.id.action_search)
        val searchView = item.actionView as SearchView
        searchView.queryHint = getString(R.string.menu_search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                query = newText ?: ""
                refreshList()
                return true
            }
        })
    }

    private fun handleMenu(id: Int): Boolean {
        when (id) {
            R.id.action_sort -> showSortDialog()
            R.id.action_open_file -> openDocument.launch(arrayOf("video/*", "application/octet-stream"))
            R.id.action_open_url -> showUrlDialog()
            R.id.action_default -> showDefaultDialog()
            R.id.action_update -> checkUpdate(true)
            R.id.action_paste -> pasteVideo()
            R.id.action_selection_share -> shareSelected()
            R.id.action_selection_delete -> deleteSelected()
            R.id.action_refresh -> {
                if (hasPermission()) loadVideos() else requestPermissionOrSettings()
            }
            else -> return false
        }
        return true
    }

    private fun showSortDialog() {
        val names = arrayOf(
            getString(R.string.sort_newest),
            getString(R.string.sort_name),
            getString(R.string.sort_duration),
            getString(R.string.sort_size)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(names, Prefs.getSort(this)) { dialog, which ->
                Prefs.setSort(this, which)
                refreshList()
                dialog.dismiss()
            }
            .show()
    }

    private fun showUrlDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.dialog_url_hint)
        input.setSingleLine()
        val pad = (20 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_url_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_open) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) startPlayerWithUri(Uri.parse(text))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showDefaultDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.default_title)
            .setMessage(R.string.default_message)
            .setPositiveButton(R.string.default_open_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e2: Exception) {
                        Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    // ---------- Auto update ----------

    private fun maybeCheckUpdate() {
        if (updateChecked) return
        updateChecked = true
        checkUpdate(false)
    }

    /** Cek rilis terbaru di GitHub. [manual] = true berarti dipicu dari menu (beri umpan balik). */
    private fun checkUpdate(manual: Boolean) {
        Thread {
            val release = Updater.fetchLatest()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                when {
                    release == null -> {
                        if (manual) Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show()
                    }
                    Updater.isNewer(release.version, BuildConfig.VERSION_NAME) -> {
                        lastRelease = release
                        showUpdateDialog(release)
                        postUpdateNotification(release)
                    }
                    else -> {
                        if (manual) Toast.makeText(this, R.string.update_none, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun postUpdateNotification(release: Updater.Release) {
        val needPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            if (!askedNotifPermission) {
                askedNotifPermission = true
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            Updater.notifyUpdate(this, release)
        }
    }

    private fun showUpdateDialog(release: Updater.Release) {
        val notes = if (release.notes.isEmpty()) "" else "\n\n" + release.notes.take(600)
        AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_message, release.version, BuildConfig.VERSION_NAME, notes))
            .setPositiveButton(R.string.update_now) { _, _ -> openReleasePage(release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun openReleasePage(release: Updater.Release) {
        // Pengguna mengunduh APK dan menyetujui pemasangan melalui Android.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Memutar ----------

    private fun startPlayerWithUri(uri: Uri) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    private fun pasteVideo() {
        val source = pendingCopy ?: return
        val destinationFolder = currentFolderName.takeIf { currentFolderId != null && it.isNotBlank() }
        binding.progress.visibility = View.VISIBLE
        executor.execute {
            var outputUri: Uri? = null
            try {
                val resolver = contentResolver
                val extension = source.name.substringAfterLast(".", "").takeIf { it.isNotBlank() } ?: "mp4"
                val baseName = source.name.substringBeforeLast(".", source.name)
                val copyName = "$baseName (salinan).$extension"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, copyName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/$extension".lowercase())
                    if (Build.VERSION.SDK_INT >= 29) {
                        val folder = destinationFolder?.let { "${Environment.DIRECTORY_MOVIES}/$it" } ?: Environment.DIRECTORY_MOVIES
                        put(MediaStore.Video.Media.RELATIVE_PATH, folder)
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    } else {
                        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), destinationFolder ?: "")
                        if (!folder.exists()) folder.mkdirs()
                        put(MediaStore.Video.Media.DATA, File(folder, copyName).absolutePath)
                    }
                }
                val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                outputUri = resolver.insert(collection, values) ?: throw IllegalStateException("Tidak dapat membuat file tujuan")
                resolver.openInputStream(source.uri).use { input ->
                    if (input == null) throw IllegalStateException("File sumber tidak dapat dibaca")
                    resolver.openOutputStream(outputUri!!, "w").use { output ->
                        if (output == null) throw IllegalStateException("File tujuan tidak dapat ditulis")
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    resolver.update(outputUri!!, done, null, null)
                }
                mainHandler.post {
                    if (!isDestroyed) {
                        pendingCopy = null
                        binding.toolbar.menu.findItem(R.id.action_paste)?.isVisible = false
                        binding.progress.visibility = View.GONE
                        Toast.makeText(this, "Salinan video berhasil dibuat", Toast.LENGTH_SHORT).show()
                        loadVideos()
                    }
                }
            } catch (e: Exception) {
                outputUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                mainHandler.post {
                    if (!isDestroyed) {
                        binding.progress.visibility = View.GONE
                        Toast.makeText(this, "Gagal menyalin video: ${e.message ?: "kesalahan file"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    private fun playVideo(video: VideoItem) {
        // Putar bersama video lain di folder yang sama sebagai daftar putar
        val sameFolder = sortVideos(allVideos.filter { it.bucketId == video.bucketId })
        val index = sameFolder.indexOfFirst { it.id == video.id }.coerceAtLeast(0)

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putStringArrayListExtra(
            PlayerActivity.EXTRA_URIS,
            ArrayList(sameFolder.map { it.uri.toString() })
        )
        intent.putStringArrayListExtra(
            PlayerActivity.EXTRA_TITLES,
            ArrayList(sameFolder.map { it.name })
        )
        intent.putExtra(PlayerActivity.EXTRA_INDEX, index)
        startActivity(intent)
    }
}
