package com.vidgrab.app.core

import android.content.Context
import android.content.SharedPreferences

/** 下载性能设置：分片并发数、aria2c 多线程下载。 */
object DownloadSettings {

    const val DEFAULT_FRAGMENTS = 8
    val FRAGMENT_OPTIONS = listOf(4, 8, 16)

    private const val PREFS = "download_settings"
    private const val KEY_FRAGMENTS = "fragments"
    private const val KEY_ARIA2C = "aria2c"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var fragments: Int
        get() = prefs?.getInt(KEY_FRAGMENTS, DEFAULT_FRAGMENTS) ?: DEFAULT_FRAGMENTS
        set(value) {
            prefs?.edit()?.putInt(KEY_FRAGMENTS, value.coerceIn(1, 32))?.apply()
        }

    var aria2c: Boolean
        get() = prefs?.getBoolean(KEY_ARIA2C, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_ARIA2C, value)?.apply()
        }
}
