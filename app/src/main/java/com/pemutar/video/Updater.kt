package com.pemutar.video

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pemeriksa pembaruan melalui GitHub Releases.
 * Aplikasi hanya memberi tahu dan membuka halaman rilis; pengunduhan serta
 * pemasangan APK dilakukan secara sadar oleh pengguna melalui Android.
 */
object Updater {

    const val REPO = "ardhikarijal-source/video-player"
    private const val CHANNEL_ID = "update"
    private const val NOTIF_ID = 1001

    data class Release(
        val version: String,
        val notes: String,
        val releaseUrl: String
    )

    /** Ambil rilis publik terbaru. Panggil dari thread latar belakang. */
    fun fetchLatest(): Release? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PemutarVideo")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonText)
            val tag = json.optString("tag_name").trim()
            val releaseUrl = json.optString("html_url").trim()
            if (tag.isEmpty() || releaseUrl.isEmpty()) return null

            return Release(
                version = tag.removePrefix("v").removePrefix("V"),
                notes = json.optString("body").trim(),
                releaseUrl = releaseUrl
            )
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /** Membandingkan versi angka, misalnya 1.10 lebih baru dari 1.9. */
    fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = parse(remote)
        val localParts = parse(local)
        val count = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until count) {
            val remotePart = remoteParts.getOrElse(i) { 0 }
            val localPart = localParts.getOrElse(i) { 0 }
            if (remotePart != localPart) return remotePart > localPart
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.split('.', '-', '+').map { part ->
            part.filter { it.isDigit() }.toIntOrNull() ?: 0
        }

    fun notifyUpdate(context: Context, release: Release) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val openRelease = Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl))
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            openRelease,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(context.getString(R.string.notif_update_title))
            .setContentText(context.getString(R.string.notif_update_text, release.version))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIF_ID, notification)
    }
}
