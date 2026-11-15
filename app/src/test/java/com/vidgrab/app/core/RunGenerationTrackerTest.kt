package com.vidgrab.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直链下载「暂停 → 继续」卡死的根因在代次管理上：
 * 取消必须是「按代次」的，否则新一次运行会把上一代线程的取消标记清掉，
 * 让它们在后台复活、继续写同一个 .part。
 */
class RunGenerationTrackerTest {

    @Test
    fun cancelMarksCurrentGeneration() {
        val tracker = RunGenerationTracker()
        val gen = tracker.begin("t1")
        tracker.cancel("t1")
        assertTrue(tracker.isCancelled("t1", gen))
    }

    @Test
    fun newRunStartsCleanAfterPreviousCancel() {
        val tracker = RunGenerationTracker()
        val first = tracker.begin("t1")
        tracker.cancel("t1")
        assertTrue(tracker.isCancelled("t1", first))

        // 暂停后立刻继续：新代次不受上一次取消的影响
        val second = tracker.begin("t1")
        assertFalse(tracker.isCancelled("t1", second))
    }

    @Test
    fun previousRunStillSeesItsOwnCancel() {
        val tracker = RunGenerationTracker()
        val first = tracker.begin("t1")
        tracker.cancel("t1")
        tracker.begin("t1")

        // 关键：旧线程必须仍然认为自己是已取消的，否则会继续往 .part 里写
        assertTrue(tracker.isCancelled("t1", first))
    }

    @Test
    fun cancelOnlyTargetsLatestGeneration() {
        val tracker = RunGenerationTracker()
        val first = tracker.begin("t1")
        val second = tracker.begin("t1")
        tracker.cancel("t1")

        assertTrue(tracker.isCancelled("t1", second))
        assertFalse(tracker.isCancelled("t1", first))
    }

    @Test
    fun endOfOldGenerationKeepsNewerCancel() {
        val tracker = RunGenerationTracker()
        val first = tracker.begin("t1")
        val second = tracker.begin("t1")
        tracker.cancel("t1")

        tracker.end("t1", first)
        assertTrue(tracker.isCancelled("t1", second))
    }

    @Test
    fun endClearsOwnRecords() {
        val tracker = RunGenerationTracker()
        val gen = tracker.begin("t1")
        tracker.cancel("t1")
        tracker.end("t1", gen)

        // 代次结束后自己的取消标记随之清理，任务号不会无限堆积
        assertFalse(tracker.isCancelled("t1", gen))
    }

    @Test
    fun tasksAreIsolated() {
        val tracker = RunGenerationTracker()
        val a = tracker.begin("a")
        val b = tracker.begin("b")
        tracker.cancel("a")

        assertTrue(tracker.isCancelled("a", a))
        assertFalse(tracker.isCancelled("b", b))
    }
}
