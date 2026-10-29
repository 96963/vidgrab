package com.vidgrab.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long
)

data class BookmarkEntry(
    val url: String,
    val title: String,
    val savedAt: Long
)

/** 浏览器历史与书签的持久化存储。 */
object BrowserStore {

    private const val FILE_NAME = "browser_data.json"
    private const val MAX_HISTORY = 500

    private var file: File? = null

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<BookmarkEntry>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkEntry>> = _bookmarks.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, FILE_NAME)
        load()
    }

    @Synchronized
    private fun load() {
        runCatching {
            val target = file ?: return
            if (!target.exists()) return
            val root = JSONObject(target.readText())
            val historyArray = root.optJSONArray("history") ?: JSONArray()
            _history.value = (0 until historyArray.length()).mapNotNull { index ->
                val item = historyArray.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").ifBlank { return@mapNotNull null }
                HistoryEntry(
                    url = url,
                    title = item.optString("title", url),
                    visitedAt = item.optLong("time", 0L)
                )
            }
            val bookmarkArray = root.optJSONArray("bookmarks") ?: JSONArray()
            _bookmarks.value = (0 until bookmarkArray.length()).mapNotNull { index ->
                val item = bookmarkArray.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").ifBlank { return@mapNotNull null }
                BookmarkEntry(
                    url = url,
                    title = item.optString("title", url),
                    savedAt = item.optLong("time", 0L)
                )
            }
        }
    }

    @Synchronized
    private fun persist() {
        val target = file ?: return
        runCatching {
            val root = JSONObject()
            val historyArray = JSONArray()
            _history.value.forEach { entry ->
                historyArray.put(JSONObject().apply {
                    put("url", entry.url)
                    put("title", entry.title)
                    put("time", entry.visitedAt)
                })
            }
            val bookmarkArray = JSONArray()
            _bookmarks.value.forEach { entry ->
                bookmarkArray.put(JSONObject().apply {
                    put("url", entry.url)
                    put("title", entry.title)
                    put("time", entry.savedAt)
                })
            }
            root.put("history", historyArray)
            root.put("bookmarks", bookmarkArray)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(target)) {
                target.writeText(root.toString())
                temp.delete()
            }
        }
    }

    @Synchronized
    fun addHistory(url: String, title: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: url
        val current = _history.value
        if (current.firstOrNull()?.url == url) return
        _history.value = (listOf(HistoryEntry(url, cleanTitle, System.currentTimeMillis())) +
            current.filterNot { it.url == url }).take(MAX_HISTORY)
        persist()
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        persist()
    }

    fun isBookmarked(url: String): Boolean = _bookmarks.value.any { it.url == url }

    @Synchronized
    fun addBookmark(url: String, title: String?) {
        if (url.isBlank()) return
        if (_bookmarks.value.any { it.url == url }) return
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: url
        _bookmarks.value = listOf(BookmarkEntry(url, cleanTitle, System.currentTimeMillis())) +
            _bookmarks.value
        persist()
    }

    @Synchronized
    fun removeBookmark(url: String) {
        _bookmarks.value = _bookmarks.value.filterNot { it.url == url }
        persist()
    }
}
