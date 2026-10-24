package com.example.autosnap

import android.content.Context

object Config {
    const val SNAP_PKG = "com.snapchat.android"

    // Screen positions as fractions (x, y) - fallback taps if text lookup fails.
    // Adjust these if your phone's Snapchat layout differs.
    const val SHUTTER_X = 0.5f
    const val SHUTTER_Y = 0.82f
    const val SEND_TO_X = 0.85f
    const val SEND_TO_Y = 0.93f
    const val SEND_X = 0.88f
    const val SEND_Y = 0.93f

    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun hour(c: Context) = p(c).getInt("hour", 20)
    fun minute(c: Context) = p(c).getInt("minute", 0)
    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun friend(c: Context) = p(c).getString("friend", "") ?: ""

    fun save(c: Context, h: Int, m: Int, en: Boolean, friend: String) {
        p(c).edit()
            .putInt("hour", h).putInt("minute", m)
            .putBoolean("enabled", en).putString("friend", friend.trim())
            .apply()
    }
}
