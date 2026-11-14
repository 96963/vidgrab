package com.vidgrab.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

/** 发布到公共下载目录的结果：失败时把原因带回界面，不再静默回退。 */
sealed interface PublishResult {
    data class Success(val ref: String) : PublishResult
    data class Failure(val message: String) : PublishResult
}

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

    /**
     * 复制成品到公共目录。
     * @param onProgress 复制进度 0..1，供 UI 在“处理中”显示。
     * @return Success 带公共目录引用；Failure 带可直接展示给用户的原因。
     */
    fun publish(
        context: Context,
        file: File,
        onProgress: (Float) -> Unit = {}
    ): PublishResult {
        if (!file.isFile) return PublishResult.Failure("源文件不存在，无法保存到公共目录")

        val name = FileNames.publicFileName(file.name)
        val mime = mimeOf(file.extension)
        val required = file.length() + 16L * 1024 * 1024
        val available = availableBytes()
        if (available in 0 until required) {
            return PublishResult.Failure(
                "剩余空间不足：需要约 ${formatSize(required)}，可用 ${formatSize(available)}；" +
                    "文件已保留在应用私有目录"
            )
        }
        return try {
            val saveTree = DownloadSettings.saveTreeUri
            val ref = when {
                saveTree != null -> publishToTree(context, saveTree, file, name, mime, onProgress)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    publishToMediaStore(context, file, name, mime, onProgress)
                else -> publishToPublicDir(file, name, onProgress)
            }
            if (ref == null) {
                PublishResult.Failure("无法写入保存目录，文件已保留在应用私有目录")
            } else {
                PublishResult.Success(ref)
            }
        } catch (e: Throwable) {
            PublishResult.Failure(
                "写入保存目录失败：${e.message ?: e.javaClass.simpleName}；文件已保留在应用私有目录"
            )
        }
    }

    /** SAF 自定义目录：通过用户授权的 DocumentFile 树写入。 */
    private fun publishToTree(
        context: Context,
        treeUri: String,
        file: File,
        name: String,
        mime: String,
        onProgress: (Float) -> Unit
    ): String? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
        if (!tree.exists() || !tree.canWrite()) error("保存目录不可写，请重新选择")
        var targetName = name
        var index = 2
        while (tree.findFile(targetName) != null) {
            targetName = indexedName(name, index)
            index++
        }
        val doc = tree.createFile(mime, targetName) ?: error("无法在保存目录创建文件")
        try {
            context.contentResolver.openOutputStream(doc.uri)?.use { output ->
                file.inputStream().use { input ->
                    copyWithProgress(input, output, file.length(), onProgress)
                }
            } ?: error("无法写入保存目录")
            val written = runCatching {
                context.contentResolver.openFileDescriptor(doc.uri, "r")?.use { it.statSize } ?: -1L
            }.getOrDefault(-1L)
            if (written in 0 until file.length()) {
                error("写入不完整（${formatSize(written)} / ${formatSize(file.length())}）")
            }
            runCatching { file.delete() }
            return doc.uri.toString()
        } catch (e: Throwable) {
            runCatching { doc.delete() }
            throw e
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(
        context: Context,
        file: File,
        name: String,
        mime: String,
        onProgress: (Float) -> Unit
    ): String? {
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
                file.inputStream().use { input ->
                    copyWithProgress(input, output, file.length(), onProgress)
                }
            } ?: error("无法写入公共下载目录")

            // 确认写入完整：空间不足时流可能被提前关闭
            val written = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            }.getOrDefault(-1L)
            if (written in 0 until file.length()) {
                error("写入不完整（${formatSize(written)} / ${formatSize(file.length())}）")
            }

            val visible = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            if (visible <= 0) error("无法完成公共目录写入（IS_PENDING 未清除）")
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        // 公共副本已就绪，私有文件能删就删（删不掉不影响结果）
        runCatching { file.delete() }
        return uri.toString()
    }

    private fun publishToPublicDir(
        file: File,
        name: String,
        onProgress: (Float) -> Unit
    ): String? {
        val dir = publicDir() ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        var target = File(dir, name)
        var index = 2
        while (target.exists()) {
            target = File(dir, indexedName(name, index))
            index++
        }
        file.inputStream().use { input ->
            target.outputStream().use { output ->
                copyWithProgress(input, output, file.length(), onProgress)
            }
        }
        val written = target.length()
        if (written < file.length()) {
            runCatching { target.delete() }
            error("写入不完整（${formatSize(written)} / ${formatSize(file.length())}）")
        }
        runCatching { file.delete() }
        return target.absolutePath
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        onProgress: (Float) -> Unit
    ) {
        val buffer = ByteArray(1024 * 1024)
        var copied = 0L
        var lastReport = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            copied += read
            val now = System.currentTimeMillis()
            if (now - lastReport >= 300) {
                lastReport = now
                onProgress(if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else 0f)
            }
        }
        output.flush()
        onProgress(1f)
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

    /** 改显示名（保留扩展名）；成功返回新的引用（SAF 重命名后 URI 可能变化）。 */
    fun rename(context: Context, ref: String, newBase: String): String? {
        if (!isContentRef(ref)) return null
        val current = info(context, ref)?.name ?: return null
        val ext = current.substringAfterLast('.', "")
        val name = if (ext.isBlank()) newBase else "$newBase.$ext"
        val uri = Uri.parse(ref)
        val updated = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.renameDocument(context.contentResolver, uri, name)?.toString()
            } else {
                val changed = context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
                    null,
                    null
                )
                if (changed > 0) ref else null
            }
        }.getOrNull()
        return updated
    }

    fun delete(context: Context, ref: String): Boolean {
        if (!isContentRef(ref)) return false
        val uri = Uri.parse(ref)
        return runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
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

    /** 公共目录所在卷的可用字节；取不到时返回 -1，表示不做空间判断。 */
    private fun availableBytes(): Long = runCatching {
        @Suppress("DEPRECATION")
        val path = Environment.getExternalStorageDirectory()
        StatFs(path.absolutePath).availableBytes
    }.getOrDefault(-1L)

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

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return "未知"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
