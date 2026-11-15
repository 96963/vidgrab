package com.vidgrab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动重试判定的回归测试：网络类错误必须能重试，业务类错误必须不重试。
 * 纯 JVM 测试，不依赖 Android 环境，`gradle testDebugUnitTest` 即可跑。
 */
class RetryPolicyTest {

    @Test
    fun timeoutIsRetryable() {
        assertTrue(RetryPolicy.isRetryableNetworkError("ERROR: Read timed out"))
        assertTrue(RetryPolicy.isRetryableNetworkError("socket timeout"))
    }

    @Test
    fun connectionDropIsRetryable() {
        assertTrue(RetryPolicy.isRetryableNetworkError("Connection reset by peer"))
        assertTrue(RetryPolicy.isRetryableNetworkError("[Errno 104] Connection reset by peer"))
        assertTrue(RetryPolicy.isRetryableNetworkError("IncompleteRead(3226 bytes read)"))
        assertTrue(RetryPolicy.isRetryableNetworkError("Network is unreachable"))
        assertTrue(RetryPolicy.isRetryableNetworkError("Failed to resolve host"))
    }

    @Test
    fun serverErrorsAreRetryable() {
        assertTrue(RetryPolicy.isRetryableNetworkError("HTTP Error 502: Bad Gateway"))
        assertTrue(RetryPolicy.isRetryableNetworkError("HTTP Error 503: Service Unavailable"))
        assertTrue(RetryPolicy.isRetryableNetworkError("HTTP Error 504: Gateway Timeout"))
    }

    @Test
    fun forbiddenAndNotFoundAreNotRetryable() {
        assertFalse(RetryPolicy.isRetryableNetworkError("HTTP Error 403: Forbidden"))
        assertFalse(RetryPolicy.isRetryableNetworkError("HTTP Error 404: Not Found"))
        assertFalse(RetryPolicy.isRetryableNetworkError("Video unavailable"))
    }

    @Test
    fun parseFailuresAreNotRetryable() {
        assertFalse(RetryPolicy.isRetryableNetworkError("Unable to extract video data"))
        assertFalse(RetryPolicy.isRetryableNetworkError("No video formats found"))
        assertFalse(RetryPolicy.isRetryableNetworkError("Unsupported URL"))
    }

    @Test
    fun loginAndRegionAreNotRetryable() {
        assertFalse(RetryPolicy.isRetryableNetworkError("Sign in to confirm you're not a bot"))
        assertFalse(RetryPolicy.isRetryableNetworkError("This video is not available in your country"))
        assertFalse(RetryPolicy.isRetryableNetworkError("Private video, login required"))
    }

    @Test
    fun blankAndUnknownAreNotRetryable() {
        assertFalse(RetryPolicy.isRetryableNetworkError(null))
        assertFalse(RetryPolicy.isRetryableNetworkError("   "))
        assertFalse(RetryPolicy.isRetryableNetworkError("something odd happened"))
    }

    @Test
    fun fatalSignalWinsOverNetworkSignal() {
        // 403 文案里常夹带 connection / timeout 字样，但仍属业务错误，不能重试
        assertFalse(
            RetryPolicy.isRetryableNetworkError(
                "HTTP Error 403: Forbidden (connection refused by proxy)"
            )
        )
        assertFalse(
            RetryPolicy.isRetryableNetworkError(
                "Unable to extract video data: read timed out"
            )
        )
    }

    @Test
    fun backoffGrowsAndClamps() {
        assertEquals(3000L, RetryPolicy.backoffFor(0))
        assertEquals(8000L, RetryPolicy.backoffFor(1))
        assertEquals(20000L, RetryPolicy.backoffFor(2))
        assertEquals(20000L, RetryPolicy.backoffFor(9))
    }

    @Test
    fun retryLimitIsThree() {
        assertEquals(3, RetryPolicy.MAX_AUTO_RETRY)
    }
}
