package com.vidgrab.app.core

import android.content.Context
import java.io.File

/**
 * 临时诊断日志：vivo 等系统会屏蔽第三方 App 的 logcat 输出，
 * 诊断信息统一写到 files/vglog.txt，用 run-as cat 读取。
 *
 * 高频日志（例如每个资源请求都记一条的嗅探日志）用 verbose=true，
 * 只在 debug 构建落盘，正式版不写，避免长期使用刷爆存储。
 */
object DiagLog {

    /** 单个日志文件上限，超过就裁到 [TRIM_TO_BYTES]。 */
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val TRIM_TO_BYTES = 2L * 1024 * 1024

    private val lock = Any()

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        if (file == null) {
            file = File(context.filesDir, "vglog.txt")
        }
    }

    fun w(tag: String, msg: String, verbose: Boolean = false) {
        if (verbose && !com.vidgrab.app.BuildConfig.DEBUG) return
        val f = file ?: return
        val line = "[%s] %s: %s%n".format(
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()),
            tag,
            msg
        )
        synchronized(lock) {
            runCatching {
                f.appendText(line)
                if (f.length() > MAX_BYTES) trim(f)
            }
        }
    }

    /** 超过上限时只保留最后一段，保证日志不会无限增长。 */
    private fun trim(f: File) {
        runCatching {
            val bytes = f.readBytes()
            if (bytes.size <= TRIM_TO_BYTES) return
            val keep = bytes.copyOfRange(bytes.size - TRIM_TO_BYTES.toInt(), bytes.size)
            f.writeBytes(keep)
        }
    }
}
