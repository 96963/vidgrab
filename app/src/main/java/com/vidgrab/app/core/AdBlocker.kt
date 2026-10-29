package com.vidgrab.app.core

import android.content.Context
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Collections

/**
 * 广告拦截：
 *  - ad_rules.txt：由 EasyList / EasyPrivacy / EasyList China 生成的域名规则（B/T/A）
 *  - ad_hosts.txt：人工维护的补充域名
 *  - 路径规则与可疑子域启发式
 * 匹配时按域名后缀逐级查表，避免遍历大列表。
 */
object AdBlocker {

    @Volatile
    private var blockHosts: Set<String> = Collections.emptySet()

    @Volatile
    private var thirdPartyHosts: Set<String> = Collections.emptySet()

    @Volatile
    private var allowHosts: Set<String> = Collections.emptySet()

    @Volatile
    private var loaded = false

    private val suspiciousLabels = setOf(
        "ads", "adserver", "adservice", "advert", "popads", "popcash",
        "popunder", "adsystem", "adnxs", "doubleclick"
    )

    private val pathRules = listOf(
        Regex("""(?i)/(ad|ads|adserver|adservice|advert|advertisement|popunder|popup|prebid|vast|preroll|banner|banners)(/|$|\?|\.)"""),
        Regex("""(?i)[?&](adunit|adid|clickid|popunder|ad_type|adzone)=""")
    )

    private val twoLevelSuffixes = setOf("com", "net", "org", "gov", "edu", "co", "ac")

    fun load(context: Context) {
        if (loaded) return
        val block = HashSet<String>(110_000)
        val third = HashSet<String>(8_000)
        val allow = HashSet<String>(1_500)

        readLines(context, "ad_hosts.txt").forEach { block.add(it) }
        readLines(context, "ad_rules.txt").forEach { line ->
            when {
                line.startsWith("B ") -> block.add(line.substring(2))
                line.startsWith("T ") -> third.add(line.substring(2))
                line.startsWith("A ") -> allow.add(line.substring(2))
            }
        }
        block.removeAll(allow)
        third.removeAll(allow)

        blockHosts = block
        thirdPartyHosts = third
        allowHosts = allow
        loaded = true
    }

    private fun readLines(context: Context, asset: String): List<String> = runCatching {
        context.assets.open(asset).bufferedReader().use { reader ->
            reader.lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    }.getOrDefault(emptyList())

    /**
     * @param pageUrl 当前页面地址，用于判断第三方请求；为空时不做第三方规则匹配。
     */
    fun isBlocked(url: String, pageUrl: String? = null): Boolean {
        if (!loaded) return false
        if (SiteSettings.isAdBlockDisabled(pageUrl)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false

        if (matches(allowHosts, host)) return false
        if (matches(blockHosts, host)) return true
        if (matches(thirdPartyHosts, host) && isThirdParty(host, pageUrl)) return true

        if (host.split('.').any { it in suspiciousLabels }) return true

        val path = (uri.path ?: "") + "?" + (uri.query ?: "")
        return pathRules.any { it.containsMatchIn(path) }
    }

    private fun matches(hosts: Set<String>, host: String): Boolean {
        var current = host
        while (true) {
            if (hosts.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }

    private fun isThirdParty(host: String, pageUrl: String?): Boolean {
        val pageHost = pageUrl
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?.lowercase()
            ?: return false
        return SiteSettings.siteKey(host) != SiteSettings.siteKey(pageHost)
    }

    fun emptyResponse() = android.webkit.WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "No Content",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )
}
