package com.yausername.aria2c

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_common.SharedPrefsHelper
import com.yausername.youtubedl_common.SharedPrefsHelper.update
import com.yausername.youtubedl_common.utils.ZipUtils.unzip
import org.apache.commons.io.FileUtils
import java.io.File

/**
 * aria2c 外部下载器：从 jniLibs 的 libaria2c.zip.so 解压依赖库到
 * noBackupFilesDir/packages/aria2c，供 yt-dlp 的 --downloader libaria2c.so 使用。
 */
object Aria2c {
    private var initialized = false
    private var binDir: File? = null

    @Synchronized
    fun init(appContext: Context) {
        if (initialized) return
        val baseDir = File(appContext.noBackupFilesDir, baseName)
        if (!baseDir.exists()) baseDir.mkdir()
        binDir = File(appContext.applicationInfo.nativeLibraryDir)
        val packagesDir = File(baseDir, packagesRoot)
        val aria2cDir = File(packagesDir, aria2cDirName)
        initAria2c(appContext, aria2cDir)
        initialized = true
    }

    private fun initAria2c(appContext: Context, aria2cDir: File) {
        val aria2cLib = File(binDir, aria2cLibName)
        if (!aria2cLib.exists()) return
        // using size of lib as version
        val aria2cSize = aria2cLib.length().toString()
        if (!aria2cDir.exists() || shouldUpdateAria2c(appContext, aria2cSize)) {
            FileUtils.deleteQuietly(aria2cDir)
            aria2cDir.mkdirs()
            try {
                unzip(aria2cLib, aria2cDir)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(aria2cDir)
                throw YoutubeDLException("failed to initialize", e)
            }
            updateAria2c(appContext, aria2cSize)
        }
    }

    private fun shouldUpdateAria2c(appContext: Context, version: String): Boolean {
        return version != SharedPrefsHelper[appContext, aria2cLibVersion]
    }

    private fun updateAria2c(appContext: Context, version: String) {
        update(appContext, aria2cLibVersion, version)
    }

    @JvmStatic
    fun getInstance() = this

    private const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val aria2cDirName = "aria2c"
    private const val aria2cLibName = "libaria2c.zip.so"
    private const val aria2cLibVersion = "aria2cLibVersion"
}
