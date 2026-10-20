package io.meld.sdk.hosted

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal fun interface Cancellable {
    fun cancel()
}

/** Main-thread timers; injected so the hosted session runs on a virtual clock in JVM tests. */
internal interface Scheduler {
    /** Whether the caller is on the thread that runs this scheduler's work. */
    val isCurrentThread: Boolean

    fun now(): Long

    fun schedule(delayMs: Long, work: () -> Unit): Cancellable
}

internal object MainScheduler : Scheduler {
    private val main = Handler(Looper.getMainLooper())

    override val isCurrentThread: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    override fun now(): Long = SystemClock.uptimeMillis()

    override fun schedule(delayMs: Long, work: () -> Unit): Cancellable {
        val runnable = Runnable(work)
        main.postDelayed(runnable, delayMs)
        return Cancellable { main.removeCallbacks(runnable) }
    }
}

/** Whether the screen hosting the surface is in front. A wallet's sheet pauses it while the customer pays. */
internal interface HostForeground {
    val resumed: Boolean

    fun observe(onPaused: () -> Unit, onResumed: () -> Unit): Cancellable
}

/** Fails a payment sheet that never appeared. Disarmed when the host pauses for the sheet, or by the caller. */
internal class PresentationDeadline(
    private val intervalMs: Long,
    private val scheduler: Scheduler,
    private val foreground: HostForeground,
) {
    private var expiry: Cancellable? = null
    private var observer: Cancellable? = null

    fun arm(onExpired: () -> Unit) {
        disarm()
        observer = foreground.observe(onPaused = ::disarm, onResumed = {})
        expiry = scheduler.schedule(intervalMs) {
            disarm()
            onExpired()
        }
    }

    fun disarm() {
        expiry?.cancel()
        expiry = null
        observer?.cancel()
        observer = null
    }
}

/** Bounds how long a presented payment may run without an outcome. Only time the host is in front counts. */
internal class PresentationCeiling(
    private val intervalMs: Long,
    private val scheduler: Scheduler,
    private val foreground: HostForeground,
) {
    private var onExpired: (() -> Unit)? = null
    private var remainingMs = 0L
    private var resumedAt = 0L
    private var expiry: Cancellable? = null
    private var observer: Cancellable? = null

    /** [paused] starts the count when the host next resumes, for a sheet that is already up. */
    fun arm(paused: Boolean, onExpired: () -> Unit) {
        disarm()
        this.onExpired = onExpired
        remainingMs = intervalMs
        observer = foreground.observe(onPaused = ::pause, onResumed = ::resume)
        if (!paused) resume()
    }

    fun disarm() {
        expiry?.cancel()
        expiry = null
        onExpired = null
        observer?.cancel()
        observer = null
    }

    private fun pause() {
        val running = expiry ?: return
        running.cancel()
        expiry = null
        remainingMs -= scheduler.now() - resumedAt
    }

    private fun resume() {
        if (onExpired == null || expiry != null) return
        resumedAt = scheduler.now()
        expiry = scheduler.schedule(maxOf(remainingMs, 0)) {
            val expired = onExpired
            disarm()
            expired?.invoke()
        }
    }
}
