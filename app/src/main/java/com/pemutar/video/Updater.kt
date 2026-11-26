package com.pemutar.video

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto update lewat GitHub Releases.
 *
 * Aturan rilis (dilakukan pemilik repo):
 *  - tag rilis berbentuk angka versi, mis. "v1.2" atau "1.2" (harus sama dengan versionName di Gradle)
 *  - rilis harus publik, bukan draft dan bukan pre-release
 *  - lampirkan file .apk sebagai asset rilis
 */
object Updater {

    const val REPO = "ardhikarijal-source/video-player"
    private const val CHANNEL_ID = "update"
    private const val NOTIF_ID = 1001

    data class Release(val version: String, val notes: String, val apkUrl: String)

    /** Ambil rilis terbaru. Panggil dari thread latar belakang. Hasil null jika gagal / tidak ada. */
    fun fetchLatest(): Release? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PemutarVideo")
            if (conn.responseCode != 200) return null

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isEmpty()) return null

            return Release(
                version = tag.removePrefix("v").removePrefix("V"),
                notes = json.optString("body").trim(),
                apkUrl = apkUrl
            )
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /** true jika [remote] lebih baru dari [local]. Contoh: "1.10" > "1.9". */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parse(remote)
        val l = parse(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.split('.', '-', '+').map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }

    // ---------------- Notifikasi ----------------

    fun notifyUpdate(context: Context, release: Release) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(context.getString(R.string.notif_update_title))
            .setContentText(context.getString(R.string.notif_update_text, release.version))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    // ---------------- Unduh & pasang ----------------

    fun apkFile(context: Context, version: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        return File(dir, "PemutarVideo-$version.apk")
    }

    fun enqueueDownload(context: Context, release: Release): Long {
        val file = apkFile(context, release.version)
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(context.getString(R.string.update_downloading))
            .setDescription("v${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file.name)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
