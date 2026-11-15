package com.vidgrab.app.core

/**
 * 下载失败的自动重试策略。
 *
 * 只认「网络层中断」（超时、连接重置/拒绝、读流中断、5xx）。业务性错误——
 * 403/404、需要登录、解析失败、地区限制、版权限制——一律不重试，因为这些
 * 等一会儿再试也不会好，只会把失败拖成几分钟。
 */
object RetryPolicy {

    /** 同一任务最多自动重试几次。 */
    const val MAX_AUTO_RETRY = 3

    /** 第 n 次重试前的等待毫秒（n 从 0 开始）：3s → 8s → 20s。 */
    val BACKOFF_MS = longArrayOf(3000L, 8000L, 20000L)

    /** 取第 attempt 次重试的等待时长，超出数组长度时复用最后一个。 */
    fun backoffFor(attempt: Int): Long = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]

    fun isRetryableNetworkError(raw: String?): Boolean {
        val text = raw?.lowercase().orEmpty()
        if (text.isBlank()) return false
        // OkHttp 断流报 EOFException，消息里常夹带 "\n not found"，别被当成 404 业务错误
        if (text.contains("eofexception")) return true
        val networkSignals = listOf(
            "timed out", "timeout", "connection reset", "connection aborted",
            "connection refused", "network is unreachable", "failed to resolve",
            "temporary failure", "remote end closed", "unexpected end of stream",
            "eof", "socket closed", "connection closed", "incomplete",
            "broken pipe", "ssl", "handshake"
        )
        val looksNetwork = networkSignals.any { text.contains(it) } ||
            Regex("http error 5\\d\\d").containsMatchIn(text) ||
            text.contains("500 internal")
        if (!looksNetwork) return false
        val fatalSignals = listOf(
            "http error 403", "http error 404", "forbidden", "not found",
            "sign in", "log in", "login required", "private video", "members-only",
            "unsupported url", "unable to extract", "no video formats", "geo",
            "not available in your country", "copyright"
        )
        return fatalSignals.none { text.contains(it) }
    }
}
