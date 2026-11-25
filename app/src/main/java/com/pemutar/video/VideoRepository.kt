package com.pemutar.video

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long,
    val bucketId: String,
    val bucketName: String,
    val width: Int,
    val height: Int
)

data class FolderItem(
    val bucketId: String,
    val name: String,
    val count: Int,
    val totalSize: Long
)

object VideoRepository {

    /** Membaca semua video dari MediaStore. Panggil dari thread latar belakang. */
    fun load(context: Context): List<VideoItem> {
        val result = ArrayList<VideoItem>()
        val collection: Uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result.add(
                        VideoItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(nameCol) ?: "Video $id",
                            durationMs = c.getLong(durCol),
                            size = c.getLong(sizeCol),
                            dateAdded = c.getLong(dateCol),
                            bucketId = c.getString(bucketIdCol) ?: "",
                            bucketName = c.getString(bucketNameCol) ?: "Lainnya",
                            width = c.getInt(wCol),
                            height = c.getInt(hCol)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Izin belum diberikan: kembalikan daftar kosong
        } catch (e: Exception) {
            // Abaikan; daftar kosong akan ditampilkan
        }
        return result
    }
}
