package com.vidgrab.app.core

import android.content.Context
import android.content.SharedPreferences

/** 全局下载设置：并发、分片、aria2c、仅 Wi-Fi、限速、保存目录、附加文件。 */
object DownloadSettings {

    const val DEFAULT_FRAGMENTS = 8
    val FRAGMENT_OPTIONS = listOf(4, 8, 16)

    const val DEFAULT_CONCURRENT = 2
    val CONCURRENT_OPTIONS = listOf(2, 3, 4)

    /** 0 = 不限速；单位 KB/s。 */
    const val DEFAULT_SPEED_LIMIT_KBPS = 0
    val SPEED_LIMIT_OPTIONS = listOf(0, 512, 1024, 2048, 4096)

    private const val PREFS = "download_settings"
    private const val KEY_FRAGMENTS = "fragments"
    private const val KEY_ARIA2C = "aria2c"
    private const val KEY_CONCURRENT = "concurrent"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_SPEED_LIMIT = "speed_limit_kbps"
    private const val KEY_SAVE_TREE = "save_tree_uri"
    private const val KEY_DESKTOP_MODE = "desktop_mode"

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

    var concurrent: Int
        get() = prefs?.getInt(KEY_CONCURRENT, DEFAULT_CONCURRENT) ?: DEFAULT_CONCURRENT
        set(value) {
            prefs?.edit()?.putInt(KEY_CONCURRENT, value.coerceIn(1, 4))?.apply()
        }

    var wifiOnly: Boolean
        get() = prefs?.getBoolean(KEY_WIFI_ONLY, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_WIFI_ONLY, value)?.apply()
        }

    var speedLimitKbps: Int
        get() = prefs?.getInt(KEY_SPEED_LIMIT, DEFAULT_SPEED_LIMIT_KBPS) ?: DEFAULT_SPEED_LIMIT_KBPS
        set(value) {
            prefs?.edit()?.putInt(KEY_SPEED_LIMIT, value.coerceIn(0, 1024 * 1024))?.apply()
        }

    /** SAF 目录；空串表示默认 Download/VidGrab（MediaStore）。 */
    var saveTreeUri: String?
        get() = prefs?.getString(KEY_SAVE_TREE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit()?.putString(KEY_SAVE_TREE, value?.takeIf { it.isNotBlank() } ?: "")?.apply()
        }

    /** 浏览器桌面模式：true 时把 WebView 的 UA 换成桌面 Chrome。 */
    var desktopMode: Boolean
        get() = prefs?.getBoolean(KEY_DESKTOP_MODE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_DESKTOP_MODE, value)?.apply()
        }
}
