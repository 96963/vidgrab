package com.vidgrab.app.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 按任务维护「第几次运行」的代次号，让取消操作只落在当前正在跑的那一代。
 *
 * 为什么需要它：直链多段下载是阻塞在 IO 线程上的，暂停时协程取消并不能立刻把线程停掉。
 * 如果取消标记只是一个 taskId → Boolean 的全局开关，那么「暂停 → 立刻继续」时，
 * 新一次运行把开关清成 false，上一轮还没退出的线程就会以为自己没被取消、
 * 继续往同一个 .part 里写，两批线程互相踩，进度永远走不完（表现为卡在某个百分比）。
 *
 * 有了代次：取消只标记 begin() 返回的那个代次，旧代次继续看到「我被取消了」并退出，
 * 新代次从干净的起点开始。
 */
internal class RunGenerationTracker {

    private val current = ConcurrentHashMap<String, Long>()
    private val cancelled = ConcurrentHashMap<String, Long>()
    private val counter = AtomicLong(0)

    /** 开始新一次运行，返回本次运行的代次号。 */
    fun begin(taskId: String): Long {
        val gen = counter.incrementAndGet()
        current[taskId] = gen
        // 这里刻意不清 cancelled：上一代的标记要留给上一代线程看
        return gen
    }

    /** 取消该任务当前正在跑的那一代；不影响之后 begin() 的新代次。 */
    fun cancel(taskId: String) {
        current[taskId]?.let { cancelled[taskId] = it }
    }

    /** 指定代次是否已被取消。 */
    fun isCancelled(taskId: String, gen: Long): Boolean = cancelled[taskId] == gen

    /** 本次运行结束，清掉自己的痕迹（只清属于自己代次的记录）。 */
    fun end(taskId: String, gen: Long) {
        if (current[taskId] == gen) current.remove(taskId)
        if (cancelled[taskId] == gen) cancelled.remove(taskId)
    }
}
