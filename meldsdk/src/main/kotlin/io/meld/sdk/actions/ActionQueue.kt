package io.meld.sdk.actions

import io.meld.sdk.hosted.MainScheduler
import io.meld.sdk.hosted.Scheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * One mounted session's off-main work. Store I/O and HTTP run on one daemon thread in submission order,
 * so a claim is durable before the request that depends on it leaves, and main never touches disk or
 * network. One queue per session, so a slow read for one order never stalls another.
 */
internal class ActionQueue(
    private val executor: ExecutorService = newActionsExecutor(),
    private val main: Scheduler = MainScheduler,
) {
    @Volatile
    private var finished = false

    /** Runs [job] after all earlier work. [onResult] runs on main, and never after [finish]. */
    fun <T> run(job: () -> T, onResult: (Result<T>) -> Unit) {
        if (finished) return
        try {
            executor.execute {
                val result = try {
                    Result.success(job())
                } catch (e: Exception) {
                    Result.failure(e)
                }
                if (!finished) main.schedule(0) { if (!finished) onResult(result) }
            }
        } catch (_: RejectedExecutionException) {
            // finish() raced this call; nothing is delivered after it anyway.
        }
    }

    /** Work already queued still runs, so a claim or submission in flight lands, but nothing more is delivered. */
    fun finish() {
        finished = true
        executor.shutdown()
    }

    companion object {
        const val THREAD_NAME = "meld-actions"

        fun newActionsExecutor(): ExecutorService = Executors.newSingleThreadExecutor { work ->
            Thread(work, THREAD_NAME).apply { isDaemon = true }
        }
    }
}
