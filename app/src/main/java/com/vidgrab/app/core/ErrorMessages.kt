package com.vidgrab.app.core

/** 把 yt-dlp / HTTP 的英文报错翻成能指导操作的中文提示。 */
data class FriendlyError(
    val message: String,
    val canUpdateYtdlp: Boolean = false,
    val canRetry: Boolean = true
)

object ErrorMessages {

    fun describe(raw: String?): FriendlyError {
        val text = raw?.trim().orEmpty()
        val lower = text.lowercase()
        return when {
            lower.contains("sign in") || lower.contains("login required") ||
                lower.contains("log in") || lower.contains("private video") ||
                lower.contains("members-only") || lower.contains("members only") ||
                lower.contains("cookies") && lower.contains("browser") ->
                FriendlyError("需要登录：请到「浏览器」登录该站点，再回来重试；下载会自动复用登录 Cookie。")

            lower.contains("http error 403") || lower.contains("forbidden") ->
                FriendlyError("服务器拒绝访问（403）：可能是 Cookie 过期、地区限制或需要登录；请先在浏览器打开该视频确认能播放。")

            lower.contains("http error 404") || lower.contains("not found") ||
                lower.contains("video unavailable") || lower.contains("has been removed") ->
                FriendlyError("视频不存在或已被删除（404），请检查链接是否有效。", canRetry = false)

            lower.contains("unsupported url") ->
                FriendlyError("该链接不是 yt-dlp 支持的视频页；如果是直链 MP4，请用浏览器下载按钮或直接粘贴直链。", canUpdateYtdlp = true)

            lower.contains("unable to extract") || lower.contains("no video formats") ||
                lower.contains("failed to parse") || lower.contains("extractor") ||
                lower.contains("nsig") || lower.contains("javascript") ->
                FriendlyError("解析失败：站点可能改版或 yt-dlp 解析器过期。请点下方「更新 yt-dlp」后重试。", canUpdateYtdlp = true)

            lower.contains("timed out") || lower.contains("timeout") ||
                lower.contains("connection") || lower.contains("network is unreachable") ||
                lower.contains("failed to resolve") ->
                FriendlyError("网络连接失败或超时：请检查网络后重试；大文件建议在 Wi-Fi 下下载。")

            lower.contains("geo") || lower.contains("not available in your country") ||
                lower.contains("region") ->
                FriendlyError("该视频在当前地区不可用（地区限制），需要对应地区的网络或登录态。", canRetry = false)

            lower.contains("age") && (lower.contains("confirm") || lower.contains("restricted")) ->
                FriendlyError("该视频有年龄限制：请先在浏览器登录并确认年龄，再重试。")

            lower.contains("copyright") || lower.contains("dmca") ->
                FriendlyError("该视频因版权限制不可下载。", canRetry = false)

            lower.contains("no space") || lower.contains("enospc") ->
                FriendlyError("存储空间不足：请清理空间后重试。")

            text.isBlank() -> FriendlyError("下载失败，原因未知；可点「重试」再试一次。")
            else -> FriendlyError(text.take(500))
        }
    }
}
