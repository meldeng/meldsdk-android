package io.meld.sdk.actions

import io.meld.sdk.hosted.Cancellable
import io.meld.sdk.hosted.Scheduler
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Runs each job inline on the caller, so a test steps through the actions thread's work in order. */
internal class DirectExecutor : AbstractExecutorService() {
    @Volatile
    private var shutdown = false

    override fun execute(command: Runnable) {
        if (shutdown) throw RejectedExecutionException()
        command.run()
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shutdown() }

    override fun isShutdown() = shutdown

    override fun isTerminated() = shutdown

    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/** A main thread for tests with a real actions thread: work posted from any thread runs when the test drains it. */
internal class QueuedMain : Scheduler {
    private val owner = Thread.currentThread()
    private val posted = ConcurrentLinkedQueue<() -> Unit>()
    private val arrivals = Semaphore(0)

    override val isCurrentThread: Boolean get() = Thread.currentThread() === owner

    override fun now(): Long = 0

    override fun schedule(delayMs: Long, work: () -> Unit): Cancellable {
        posted += work
        arrivals.release()
        return Cancellable { posted -= work }
    }

    /** Waits until [count] more results were posted from the actions thread. */
    fun awaitPosted(count: Int = 1): Boolean = arrivals.tryAcquire(count, 5, TimeUnit.SECONDS)

    fun drain(): Int {
        var ran = 0
        while (true) {
            val work = posted.poll() ?: return ran
            work()
            ran += 1
        }
    }
}
