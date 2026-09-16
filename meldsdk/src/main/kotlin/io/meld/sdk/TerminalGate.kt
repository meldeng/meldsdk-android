package io.meld.sdk

/**
 * Collapses a provider's "the customer is done paying" moment into exactly one
 * [MeldEventHandlers.onPaymentSubmitted] per mount.
 *
 * Providers disagree on how that moment arrives. Uphold's authorize widget sends only `complete`
 * and never a status. Hosted-link Apple Pay sends `commit_success` *and* `polling_start` — both
 * meaning submitted — then `polling_success` as a `completed` status later. Mercuryo's card widget
 * sends "payment finished" and a `paid` status as two unrelated messages with no ordering between
 * them. Left alone, that asymmetry lands on the integrator, who has to dedupe terminal handling or
 * watch it run twice; every one of them ends up writing the same guard.
 *
 * A terminal failed/cancelled status, a cancel, or a non-recoverable error closes the gate without
 * firing, so a failure is never followed by a submission. A *recoverable* error does not: the
 * widget is still alive and the customer may yet pay.
 *
 * Applied by [Meld.mount] to the caller's handlers, so it covers every adapter — including the ones
 * that invoke a handler directly rather than going through a host's event dispatch.
 *
 * `onStatusChange` is passed straight through, and lands before the callback synthesized from it.
 */
internal fun MeldEventHandlers.gated(): MeldEventHandlers {
    val source = this
    var closed = false

    fun open(): Boolean {
        if (closed) return false
        closed = true
        return true
    }

    return MeldEventHandlers(
        onReady = source.onReady,
        onPaymentSubmitted = { orderId -> if (open()) source.onPaymentSubmitted?.invoke(orderId) },
        onStatusChange = { change ->
            source.onStatusChange?.invoke(change)
            when (change.status) {
                MeldStatus.COMPLETED -> if (open()) source.onPaymentSubmitted?.invoke(change.orderId)
                MeldStatus.FAILED, MeldStatus.CANCELLED -> closed = true
                MeldStatus.PENDING -> Unit
            }
        },
        onCancel = { orderId ->
            closed = true
            source.onCancel?.invoke(orderId)
        },
        onError = { error ->
            if (!error.recoverable) closed = true
            source.onError?.invoke(error)
        },
    )
}
