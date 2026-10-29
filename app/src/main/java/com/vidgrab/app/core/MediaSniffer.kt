package com.vidgrab.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一条检测日志，用于排查“为什么这个站抓不到”。 */
data class DetectionLogEntry(
    val time: Long,
    val source: String,
    val url: String,
    val result: String
)

/**
 * 浏览器嗅探：网络请求 + 页面脚本 + XHR/fetch 响应内容，统一归类去重。
 */
object MediaSniffer {

    const val SRC_INTERCEPT = "请求拦截"
    const val SRC_JS = "页面脚本"
    const val SRC_RESPONSE = "响应内容"
    const val SRC_PERFORMANCE = "性能条目"
    const val SRC_ADBLOCK = "广告拦截"

    private val _items = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val items: StateFlow<List<DetectedMedia>> = _items.asStateFlow()

    private val _logs = MutableStateFlow<List<DetectionLogEntry>>(emptyList())
    val logs: StateFlow<List<DetectionLogEntry>> = _logs.asStateFlow()

    private val seen = HashSet<String>()
    private val headers = HashMap<String, Map<String, String>>()

    private val playlistRegex = Regex("""\.(m3u8|mpd)($|[?&#])""")

    @Synchronized
    fun offer(
        rawUrl: String?,
        pageUrl: String,
        pageTitle: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        source: String = SRC_INTERCEPT,
        forcedKind: String? = null
    ) {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty() || !url.startsWith("http")) return
        val kind = forcedKind ?: classify(url) ?: return
        if (!seen.add(url)) return
        if (extraHeaders.isNotEmpty()) headers[url] = extraHeaders
        _items.update { list ->
            list + DetectedMedia(url = url, kind = kind, pageUrl = pageUrl, pageTitle = pageTitle)
        }
        log(source, url, "新增 $kind")
    }

    /** 广告拦截命中时记一条，便于确认是否误伤播放依赖。 */
    fun recordBlocked(url: String) {
        log(SRC_ADBLOCK, url, "已拦截")
    }

    @Synchronized
    fun log(source: String, url: String, result: String) {
        val entry = DetectionLogEntry(System.currentTimeMillis(), source, url, result)
        _logs.update { (listOf(entry) + it).take(MAX_LOGS) }
    }

    /** 浏览器抓到的 Cookie / Referer / UA / Origin，下载与播放时原样带上。 */
    @Synchronized
    fun headersFor(url: String): Map<String, String> = headers[url] ?: emptyMap()

    fun classify(url: String): String? {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        if (playlistRegex.containsMatchIn(lower)) {
            val kind = if (lower.contains(".m3u8")) "HLS" else "DASH"
            val pathIsPlaylist = path.endsWith(".m3u8") || path.endsWith(".mpd")
            // 查询参数里带 m3u8 时，只信任 API/播放端点，避免把普通页面误判成媒体
            val looksLikeEndpoint = path.endsWith(".php") || path.endsWith(".json") ||
                path.endsWith(".asp") || path.endsWith(".aspx") ||
                path.contains("/api") || path.contains("/play") || path.contains("/stream")
            if (pathIsPlaylist || looksLikeEndpoint) return kind
        }
        return when {
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") ||
                path.endsWith(".mov") || path.endsWith(".flv") || path.endsWith(".m4v") -> "VIDEO"
            path.endsWith(".m4a") || path.endsWith(".mp3") || path.endsWith(".aac") ||
                path.endsWith(".ogg") || path.endsWith(".opus") || path.endsWith(".wav") -> "AUDIO"
            else -> null
        }
    }

    @Synchronized
    fun clear() {
        seen.clear()
        headers.clear()
        _items.value = emptyList()
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /** 注入到页面的脚本：video/audio、fetch/XHR 请求与响应、资源加载都回报给原生层。 */
    val injectedJs: String = """
        (function() {
          if (window.__vidgrabHooked) return;
          window.__vidgrabHooked = true;
          function call(name, args) {
            try {
              var bridge = window.VidGrabBridge;
              if (bridge && bridge[name]) {
                bridge[name].apply(bridge, args);
              }
            } catch (e) {}
          }
          function report(u) { if (u) call('onMediaUrl', [String(u)]); }
          function reportPlaylist(u, kind) { if (u) call('onMediaPlaylist', [String(u), kind == null ? '' : String(kind)]); }
          function reportPerf(u) { if (u) call('onPerformanceUrl', [String(u)]); }

          function playlistKind(text) {
            if (!text) return '';
            var head = String(text).slice(0, 512);
            if (head.indexOf('#EXTM3U') >= 0) return 'HLS';
            if (head.indexOf('<MPD') >= 0) return 'DASH';
            return '';
          }
          function maybePlaylist(url, ct, text) {
            if (!url || !text || text.length > 2000000) return;
            var t = String(ct || '').toLowerCase();
            var head = String(text).slice(0, 512);
            if (!(t.indexOf('mpegurl') >= 0 || t.indexOf('dash') >= 0 || t.indexOf('xml') >= 0 ||
                  t.indexOf('text') >= 0 || head.indexOf('#EXTM3U') >= 0 || head.indexOf('<MPD') >= 0)) return;
            var kind = playlistKind(text);
            if (kind) reportPlaylist(url, kind);
          }

          function scan() {
            document.querySelectorAll('video, audio, source').forEach(function(el) {
              report(el.currentSrc || el.src);
            });
          }
          var observer = new MutationObserver(scan);
          observer.observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] });

          var XHR = XMLHttpRequest.prototype;
          var origOpen = XHR.open;
          XHR.open = function(method, url) {
            this.__vgUrl = url;
            report(url);
            return origOpen.apply(this, arguments);
          };
          var origSend = XHR.send;
          XHR.send = function() {
            var xhr = this;
            try {
              xhr.addEventListener('load', function() {
                try {
                  var type = xhr.responseType;
                  if (type !== '' && type !== 'text') return;
                  var url = xhr.responseURL || xhr.__vgUrl;
                  var ct = xhr.getResponseHeader('content-type') || '';
                  maybePlaylist(url, ct, xhr.responseText);
                } catch (e) {}
              });
            } catch (e) {}
            return origSend.apply(this, arguments);
          };

          var origFetch = window.fetch;
          window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url);
            report(url);
            var p = origFetch.apply(this, arguments);
            try {
              p.then(function(resp) {
                try {
                  if (!resp || resp.type === 'opaque') return;
                  var ct = resp.headers && resp.headers.get ? (resp.headers.get('content-type') || '') : '';
                  var t = ct.toLowerCase();
                  if (t.indexOf('mpegurl') >= 0 || t.indexOf('dash') >= 0 || t.indexOf('xml') >= 0 || t.indexOf('text') >= 0) {
                    resp.clone().text().then(function(text) { maybePlaylist(resp.url || url, ct, text); }).catch(function() {});
                  }
                } catch (e) {}
              }).catch(function() {});
            } catch (e) {}
            return p;
          };

          scan();
          setInterval(function() {
            try {
              performance.getEntriesByType('resource').forEach(function(entry) { reportPerf(entry.name); });
            } catch (e) {}
          }, 3000);
        })();
    """.trimIndent()

    private const val MAX_LOGS = 100
}
