package com.vidgrab.app

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.vidgrab.app.core.CookieStore
import com.vidgrab.app.ui.syncStatusBarOnly
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 边缓冲边播放：直接把检测到的 m3u8 / mpd / mp4 交给 ExoPlayer，
 * 并带上浏览器里的 Cookie / Referer / User-Agent。
 * 支持倍速、字幕轨、亮度/音量手势、画中画与断点续播。
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var overlay: LinearLayout? = null
    private var speedButton: TextView? = null
    private var subtitleButton: TextView? = null
    private var subtitleIndex = 0
    private var currentUrl: String = ""
    private val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        currentUrl = url
        val title = intent.getStringExtra(EXTRA_TITLE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        syncStatusBarOnly(this, dark)

        val headers = readHeaders()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: DEFAULT_UA)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaItem = MediaItem.fromUri(url)
        val mediaSource = when {
            url.substringBefore('?').lowercase().endsWith(".m3u8") ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            url.substringBefore('?').lowercase().endsWith(".mpd") ->
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else ->
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.playWhenReady = true
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "播放失败：${error.errorCodeName}",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateSubtitleLabel()
            }
        })
        exoPlayer.prepare()

        val resumeMs = prefs().getLong(positionKey(url), 0L)
        if (resumeMs > 5_000) {
            exoPlayer.seekTo(resumeMs)
            Toast.makeText(this, "已从上次位置继续", Toast.LENGTH_SHORT).show()
        }

        val view = PlayerView(this).apply {
            player = exoPlayer
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
            keepScreenOn = true
        }
        playerView = view
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }
        val topBar = buildOverlay()
        overlay = topBar
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(dp(12), dp(28), dp(12), 0) }
        )
        setContentView(root)

        installGestures(view)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        title?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun buildOverlay(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val speed = overlayButton("1.0x").also { b ->
            b.setOnClickListener {
                speedIndex = (speedIndex + 1) % speeds.size
                val value = speeds[speedIndex]
                player?.playbackParameters = androidx.media3.common.PlaybackParameters(value)
                b.text = formatSpeed(value)
            }
        }
        speedButton = speed
        val subtitle = overlayButton("字幕").also { b ->
            b.setOnClickListener { cycleSubtitle() }
        }
        subtitleButton = subtitle
        val pip = overlayButton("画中画").also { b ->
            b.setOnClickListener { enterPip() }
        }
        bar.addView(speed)
        bar.addView(subtitle)
        bar.addView(pip)
        return bar
    }

    private fun overlayButton(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setBackgroundColor(0x66000000)
        isClickable = true
        isFocusable = true
    }

    private fun installGestures(view: PlayerView) {
        val manager = getSystemService(AudioManager::class.java)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null || view.height <= 0) return false
                val delta = (e1.y - e2.y) / view.height
                if (abs(delta) < 0.01f) return false
                if (e1.x < view.width / 2f) {
                    adjustBrightness(delta)
                } else {
                    adjustVolume(manager, delta)
                }
                return true
            }
        })
        view.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    private fun adjustBrightness(delta: Float) {
        val attrs = window.attributes
        val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
        attrs.screenBrightness = (current + delta).coerceIn(0.05f, 1f)
        window.attributes = attrs
    }

    private fun adjustVolume(manager: AudioManager?, delta: Float) {
        if (manager == null) return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = (max * abs(delta) * 2f).roundToInt().coerceAtLeast(1)
        manager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
    }

    private fun cycleSubtitle() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (groups.isEmpty()) {
            Toast.makeText(this, "当前视频没有字幕轨", Toast.LENGTH_SHORT).show()
            return
        }
        subtitleIndex = (subtitleIndex + 1) % (groups.size + 1)
        val params = p.trackSelectionParameters.buildUpon()
        if (subtitleIndex == 0) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            val group = groups[subtitleIndex - 1]
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        p.trackSelectionParameters = params.build()
        updateSubtitleLabel()
    }

    private fun updateSubtitleLabel() {
        val p = player ?: return
        val count = p.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT }
        subtitleButton?.text = when {
            count == 0 -> "字幕"
            subtitleIndex == 0 -> "字幕关"
            else -> "字幕${minOf(subtitleIndex, count)}"
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "系统版本不支持画中画", Toast.LENGTH_SHORT).show()
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
            .onFailure { Toast.makeText(this, "无法进入画中画", Toast.LENGTH_SHORT).show() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView?.useController = !isInPictureInPictureMode
        overlay?.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    private fun prefs() = getSharedPreferences("player_positions", MODE_PRIVATE)

    private fun positionKey(url: String) = "pos_${url.hashCode()}"

    private fun savePosition() {
        val p = player ?: return
        val pos = p.currentPosition
        if (pos > 5_000 && pos < p.duration - 3_000) {
            prefs().edit().putLong(positionKey(currentUrl), pos).apply()
        } else if (pos > 0) {
            prefs().edit().remove(positionKey(currentUrl)).apply()
        }
    }

    private fun readHeaders(): Map<String, String> {
        val keys = intent.getStringArrayExtra(EXTRA_HEADER_KEYS) ?: return emptyMap()
        val values = intent.getStringArrayExtra(EXTRA_HEADER_VALUES) ?: return emptyMap()
        return keys.zip(values).toMap()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePosition()
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADER_KEYS = "header_keys"
        private const val EXTRA_HEADER_VALUES = "header_values"

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        fun start(context: Context, url: String, title: String? = null) {
            val headers = CookieStore.headersFor(url)
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HEADER_KEYS, headers.keys.toTypedArray())
                .putExtra(EXTRA_HEADER_VALUES, headers.values.toTypedArray())
            context.startActivity(intent)
        }
    }
}
