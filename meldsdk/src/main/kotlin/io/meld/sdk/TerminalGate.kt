package io.meld.sdk

import android.util.Log

/**
 * Delivers exactly one terminal callback per mount — [MeldEventHandlers.onPaymentSubmitted],
 * `onCancel` or a non-recoverable `onError` — and drops every callback after it.
 *
 * Providers disagree on how that moment arrives. Uphold's authorize widget sends only `complete`
 * and never a status. Hosted-link Apple Pay sends `commit_success` *and* `polling_start` — both
 * meaning submitted — then `polling_success` as a `completed` status later. Mercuryo's card widget
 * sends "payment finished" and a `paid` status as two unrelated messages with no ordering between
 * them. Left alone, that asymmetry lands on the integrator, who has to dedupe terminal handling or
 * watch it run twice; every one of them ends up writing the same guard.
 *
 * A failed/cancelled status blocks the submission without being terminal itself, so a failure is
 * never followed by a submission and the adapter's own cancel or error still arrives. A
 * *recoverable* error is not terminal: the widget is still alive and the customer may yet pay.
 *
 * Applied by [Meld.mount] to the caller's handlers, so it covers every adapter — including the ones
 * that invoke a handler directly rather than going through a host's event dispatch.
 *
 * `onStatusChange` lands before the callback synthesized from it.
 */
internal fun MeldEventHandlers.gated(gate: TerminalGate = TerminalGate()): MeldEventHandlers {
    val source = this
    val onError: (MeldError) -> Unit = { error ->
        if (if (error.recoverable) gate.admits("onError") else gate.terminal("onError")) source.onError?.invoke(error)
    }

    val handlers = MeldEventHandlers(
        onReady = { orderId -> if (gate.admits("onReady")) source.onReady?.invoke(orderId) },
        onPaymentSubmitted = { orderId -> if (gate.submission()) source.onPaymentSubmitted?.invoke(orderId) },
        onStatusChange = { change ->
            if (gate.admits("onStatusChange")) {
                source.onStatusChange?.invoke(change)
                gate.observe(change.status)
                if (change.status == MeldStatus.COMPLETED && gate.submission()) {
                    source.onPaymentSubmitted?.invoke(change.orderId)
                }
            }
        },
        onCancel = { orderId -> if (gate.terminal("onCancel")) source.onCancel?.invoke(orderId) },
        onError = onError,
    )
    handlers.sessionEnded = { orderId -> gate.unannouncedEnd(orderId)?.let(onError) }
    return handlers
}

/** State shared by every handler in [gated] and by the handle that owns the mount. Main thread. */
internal class TerminalGate {
    private var terminalDelivered = false
    private var submissionBlocked = false
    private var failedSeen = false

    @Volatile
    private var released = false

    /** False once the terminal callback has been delivered; the dropped callback is logged. */
    fun admits(callback: String): Boolean {
        if (!terminalDelivered) return true
        Log.i(TAG, "dropped $callback after the terminal callback")
        return false
    }

    /** True only for the first terminal callback of the mount. */
    fun terminal(callback: String): Boolean {
        if (!admits(callback)) return false
        terminalDelivered = true
        return true
    }

    fun submission(): Boolean {
        if (submissionBlocked) {
            Log.i(TAG, "dropped onPaymentSubmitted after a failed or cancelled status")
            return false
        }
        return terminal("onPaymentSubmitted")
    }

    fun observe(status: MeldStatus) {
        when (status) {
            MeldStatus.FAILED -> {
                failedSeen = true
                submissionBlocked = true
            }
            MeldStatus.CANCELLED -> submissionBlocked = true
            MeldStatus.PENDING, MeldStatus.COMPLETED -> Unit
        }
    }

    /** The integrator unmounted, so a later teardown owes no callback. */
    fun release() {
        released = true
    }

    /** The terminal error owed by a session that ended without delivering one. */
    fun unannouncedEnd(orderId: String?): MeldError? {
        if (terminalDelivered || released) return null
        if (failedSeen) {
            return MeldError(
                orderId = orderId,
                code = MeldErrorCode.PAYMENT_REJECTED.raw,
                message = MeldErrorMessage.PAYMENT_REJECTED,
                detail = "session_ended",
                recoverable = false,
            )
        }
        return MeldError(
            orderId = orderId,
            code = MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN.raw,
            message = "The payment session ended without an outcome. Track the existing order without paying again.",
            detail = "session_ended",
            recoverable = false,
        )
    }

    private companion object {
        const val TAG = "MeldSDK"
    }
}
