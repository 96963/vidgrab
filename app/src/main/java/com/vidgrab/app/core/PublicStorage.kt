package com.vidgrab.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * 成品文件发布：下载仍在应用私有目录进行（便于续传），完成后复制到公共
 * Download/VidGrab。Android 10+ 走 MediaStore（无需存储权限），Android 9 及
 * 以下直接写公共目录。任务里保存 content:// 或公共目录绝对路径，
 * 重命名/删除/打开都按引用处理。
 */
object PublicStorage {

    private const val FOLDER = "VidGrab"
    private const val RELATIVE_PATH = "Download/$FOLDER"

    data class Info(val name: String, val size: Long)

    fun isContentRef(ref: String?): Boolean = ref?.startsWith("content://") == true

    /** 复制成品到公共目录，成功返回引用（content:// 或绝对路径），失败返回 null。 */
    fun publish(context: Context, file: File): String? {
        if (!file.isFile) return null
        val name = FileNames.clean(file.name).ifBlank { "video" }
        val mime = mimeOf(file.extension)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(context, file, name, mime)
            } else {
                publishToPublicDir(file, name)
            }
        }.getOrNull()
    }

    private fun publishToMediaStore(context: Context, file: File, name: String, mime: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入公共下载目录")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        // 公共副本已就绪，私有文件能删就删（删不掉不影响结果）
        runCatching { file.delete() }
        return uri.toString()
    }

    private fun publishToPublicDir(file: File, name: String): String? {
        val dir = publicDir() ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        var target = File(dir, name)
        var index = 2
        while (target.exists()) {
            target = File(dir, indexedName(name, index))
            index++
        }
        file.copyTo(target, overwrite = false)
        runCatching { file.delete() }
        return target.absolutePath
    }

    /** 查询文件名与大小；引用失效时返回 null。 */
    fun info(context: Context, ref: String): Info? {
        if (!isContentRef(ref)) {
            val file = File(ref)
            return if (file.isFile) Info(file.name, file.length()) else null
        }
        val uri = Uri.parse(ref)
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
                if (name.isNullOrBlank()) return@use null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    0L
                }
                Info(name, size)
            }
        }.getOrNull()
    }

    fun exists(context: Context, ref: String): Boolean = info(context, ref) != null

    /** MediaStore 里改显示名（保留扩展名）。 */
    fun rename(context: Context, ref: String, newBase: String): Boolean {
        if (!isContentRef(ref)) return false
        val current = info(context, ref)?.name ?: return false
        val ext = current.substringAfterLast('.', "")
        val name = if (ext.isBlank()) newBase else "$newBase.$ext"
        val updated = runCatching {
            context.contentResolver.update(
                Uri.parse(ref),
                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
                null,
                null
            )
        }.getOrDefault(0)
        return updated > 0
    }

    fun delete(context: Context, ref: String): Boolean {
        if (!isContentRef(ref)) return false
        return runCatching {
            context.contentResolver.delete(Uri.parse(ref), null, null) > 0
        }.getOrDefault(false)
    }

    /** 旧版本/Android 9 及以下存的是公共目录绝对路径；只删这个目录里的文件。 */
    fun deletePublicPath(ref: String?) {
        if (ref.isNullOrBlank() || isContentRef(ref)) return
        val dir = publicDir() ?: return
        val file = File(ref)
        if (file.parentFile?.absolutePath == dir.absolutePath) {
            runCatching { file.delete() }
            runCatching { File("$ref.part").delete() }
        }
    }

    private fun publicDir(): File? {
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, FOLDER)
    }

    private fun indexedName(name: String, index: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($index)${name.substring(dot)}"
        } else {
            "$name ($index)"
        }
    }

    private fun mimeOf(ext: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
}
