package io.meld.sdk.hosted

import android.util.Log
import java.util.IdentityHashMap

/** A submitted surface that can outlive its handle. */
internal interface DetachedSurface {
    fun tearDown()
}

/**
 * Keeps a submitted provider page running after the integrator releases its handle, so an app that
 * unmounts on `onPaymentSubmitted` does not unload the page while the provider is still confirming
 * the payment. Each surface is held until it reports an outcome or the grace period ends. Main thread.
 */
internal class DetachedSurfaces(private val scheduler: Scheduler) {
    private val held = IdentityHashMap<DetachedSurface, Cancellable>()

    val count: Int get() = held.size

    fun keep(surface: DetachedSurface, graceMs: Long) {
        if (held.containsKey(surface)) return
        held[surface] = scheduler.schedule(graceMs) { release(surface, "grace_period_elapsed") }
    }

    fun release(surface: DetachedSurface, outcome: String) {
        val expiry = held.remove(surface) ?: return
        expiry.cancel()
        surface.tearDown()
        Log.i(TAG, "released a detached surface: $outcome")
    }

    companion object {
        private const val TAG = "MeldSDK"

        val shared: DetachedSurfaces by lazy { DetachedSurfaces(MainScheduler) }
    }
}
