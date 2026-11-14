package com.vidgrab.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志与一键反馈：异常写入 files/crash_log.txt，设置页可导出分享。
 * 不替换系统原有处理器，写完后继续交给它，保持系统崩溃行为。
 */
object CrashReporter {

    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_BYTES = 1024L * 1024

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                kotlin.system.exitProcess(1)
            }
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.length() > MAX_BYTES) file.delete()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use { error.printStackTrace(it) }
        }.toString()
        val header = buildString {
            append("=== ").append(time).append(" ===\n")
            append("版本：").append(versionName(context)).append("\n")
            append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" / Android ").append(Build.VERSION.RELEASE)
            append(" (").append(Build.VERSION.SDK_INT).append(")\n")
            append("线程：").append(thread.name).append("\n")
            append("异常：").append(error.javaClass.name).append(": ")
            append(error.message ?: "").append("\n")
        }
        file.appendText(header + stack + "\n")
    }

    /** 分享反馈：优先附带崩溃日志；没有日志时也带上版本与设备信息。 */
    fun share(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val text = buildString {
            append("VidGrab 反馈\n")
            append("版本：").append(versionName(context)).append("\n")
            append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" / Android ").append(Build.VERSION.RELEASE)
            append(" (").append(Build.VERSION.SDK_INT).append(")\n\n")
            append("问题描述：\n")
            if (!file.exists()) append("\n（暂无崩溃日志）")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.exists()) "*/*" else "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, "反馈 VidGrab").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun logPath(context: Context): String = File(context.filesDir, FILE_NAME).absolutePath

    private fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("unknown")
}
