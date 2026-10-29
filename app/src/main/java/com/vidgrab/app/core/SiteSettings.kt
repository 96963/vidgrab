package com.vidgrab.app.core

import android.content.Context
import android.content.SharedPreferences
import java.net.URI

/** 按站点保存的偏好（目前只有广告拦截开关）。 */
object SiteSettings {

    private const val PREFS_NAME = "site_settings"
    private const val KEY_ADBLOCK_DISABLED = "adblock_disabled_sites"

    private val twoLevelSuffixes = setOf("com", "net", "org", "gov", "edu", "co", "ac")

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun siteKeyOf(pageUrl: String?): String? {
        val host = pageUrl
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?.lowercase()
            ?: return null
        return siteKey(host)
    }

    fun siteKey(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val last = parts.last()
        val secondLast = parts[parts.size - 2]
        val take = if (last.length == 2 && secondLast in twoLevelSuffixes) 3 else 2
        return parts.takeLast(take).joinToString(".")
    }

    fun isAdBlockDisabled(pageUrl: String?): Boolean {
        val key = siteKeyOf(pageUrl) ?: return false
        return prefs?.getStringSet(KEY_ADBLOCK_DISABLED, emptySet())?.contains(key) == true
    }

    @Synchronized
    fun setAdBlockDisabled(siteKey: String, disabled: Boolean) {
        val p = prefs ?: return
        val current = p.getStringSet(KEY_ADBLOCK_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (disabled) current.add(siteKey) else current.remove(siteKey)
        p.edit().putStringSet(KEY_ADBLOCK_DISABLED, current).apply()
    }
}
