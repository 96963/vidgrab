package com.vidgrab.app.core

import android.content.Context
import java.io.File

/**
 * 临时诊断日志：vivo 等系统会屏蔽第三方 App 的 logcat 输出，
 * 诊断信息统一写到 files/vglog.txt，用 run-as cat 读取。
 */
object DiagLog {

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        if (file == null) {
            file = File(context.filesDir, "vglog.txt")
        }
    }

    fun w(tag: String, msg: String) {
        runCatching {
            val f = file ?: return
            val line = "[%s] %s: %s%n".format(
                java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()),
                tag,
                msg
            )
            f.appendText(line)
        }
    }
}
