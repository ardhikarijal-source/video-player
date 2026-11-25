package com.pemutar.video

import android.content.Context
import java.util.Locale

/** Penyimpanan kecil: posisi terakhir tiap video dan pilihan urutan. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pemutar", Context.MODE_PRIVATE)

    fun getPosition(c: Context, key: String): Long = sp(c).getLong("pos_$key", 0L)

    fun setPosition(c: Context, key: String, pos: Long) {
        val e = sp(c).edit()
        if (pos <= 0L) e.remove("pos_$key") else e.putLong("pos_$key", pos)
        e.apply()
    }

    fun getSort(c: Context): Int = sp(c).getInt("sort", 0)

    fun setSort(c: Context, value: Int) {
        sp(c).edit().putInt("sort", value).apply()
    }
}

object Fmt {
    fun duration(ms: Long): String {
        val total = if (ms < 0) 0L else ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
