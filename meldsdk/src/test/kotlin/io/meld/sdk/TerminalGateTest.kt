package io.meld.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One terminal callback per mount, whichever way the provider says the customer finished. */
class TerminalGateTest {

    private class Recorder {
        val gate = TerminalGate()
        val events = mutableListOf<String>()
        val handlers = MeldEventHandlers(
            onReady = { events.add("ready") },
            onPaymentSubmitted = { events.add("submitted") },
            onStatusChange = { events.add("status:${it.status.raw}") },
            onCancel = { events.add("cancel") },
            onError = { events.add(if (it.recoverable) "error" else "error:${it.code}") },
        ).gated(gate)

        fun status(status: MeldStatus) =
            handlers.onStatusChange?.invoke(MeldStatusChange("ord_1", status, null, null))

        fun submitted() = handlers.onPaymentSubmitted?.invoke("ord_1")

        fun error(recoverable: Boolean) =
            handlers.onError?.invoke(MeldError("ord_1", "x", "x", null, recoverable))

        fun ended() = handlers.sessionEnded?.invoke("ord_1")

        fun count(kind: String) = events.count { it == kind }
    }

    /** Uphold's authorize widget: `complete` and nothing else, ever. */
    @Test
    fun `a submitted message alone fires once`() {
        val r = Recorder()

        r.submitted()

        assertEquals(1, r.count("submitted"))
    }

    /** A provider that reports its own order complete and never sends a submitted message. */
    @Test
    fun `a completed status alone fires once, after the status`() {
        val r = Recorder()

        r.status(MeldStatus.COMPLETED)

        assertEquals(listOf("status:completed", "submitted"), r.events)
    }

    /** Hosted-link Apple Pay sends commit_success then polling_start, both meaning submitted. */
    @Test
    fun `a repeated submitted message fires once`() {
        val r = Recorder()

        r.submitted()
        r.submitted()

        assertEquals(1, r.count("submitted"))
    }

    /** The same flow's later polling_success. Nothing follows the terminal callback, the status included. */
    @Test
    fun `a completed status after a submitted message does not refire`() {
        val r = Recorder()

        r.submitted()
        r.status(MeldStatus.COMPLETED)

        assertEquals(listOf("submitted"), r.events)
    }

    /** Mercuryo card sends both as unrelated messages in no guaranteed order; either wins. */
    @Test
    fun `a submitted message after a completed status does not refire`() {
        val r = Recorder()

        r.status(MeldStatus.COMPLETED)
        r.submitted()

        assertEquals(1, r.count("submitted"))
    }

    @Test
    fun `progress does not close the gate`() {
        val r = Recorder()

        r.status(MeldStatus.PENDING)
        r.submitted()

        assertEquals(1, r.count("submitted"))
    }

    /** "Payment failed" must never be followed by "payment submitted". */
    @Test
    fun `a failed status closes the gate`() {
        val r = Recorder()

        r.status(MeldStatus.FAILED)
        r.submitted()

        assertEquals(0, r.count("submitted"))
        assertEquals(1, r.count("status:failed"))
    }

    @Test
    fun `a cancelled status closes the gate`() {
        val r = Recorder()

        r.status(MeldStatus.CANCELLED)
        r.submitted()

        assertEquals(0, r.count("submitted"))
    }

    @Test
    fun `a cancel closes the gate`() {
        val r = Recorder()

        r.handlers.onCancel?.invoke("ord_1")
        r.submitted()

        assertEquals(0, r.count("submitted"))
        assertEquals(1, r.count("cancel"))
    }

    @Test
    fun `a terminal error closes the gate`() {
        val r = Recorder()

        r.error(recoverable = false)
        r.submitted()

        assertEquals(0, r.count("submitted"))
    }

    /**
     * A load failure is recoverable: the widget is still alive and the customer may yet pay, so
     * closing here would swallow the real terminal event.
     */
    @Test
    fun `a recoverable error leaves the gate open`() {
        val r = Recorder()

        r.error(recoverable = true)
        r.submitted()

        assertEquals(1, r.count("submitted"))
    }

    /** A blocked submission is not the terminal callback: the adapter's own cancel still arrives. */
    @Test
    fun `a blocked submission leaves the terminal to the adapter`() {
        val r = Recorder()

        r.status(MeldStatus.CANCELLED)
        r.submitted()
        r.status(MeldStatus.COMPLETED)
        r.handlers.onCancel?.invoke("ord_1")

        assertEquals(listOf("status:cancelled", "status:completed", "cancel"), r.events)
    }

    @Test
    fun `nothing is delivered after the terminal callback`() {
        val r = Recorder()

        r.submitted()
        r.status(MeldStatus.PENDING)
        r.handlers.onReady?.invoke("ord_1")
        r.handlers.onCancel?.invoke("ord_1")
        r.error(recoverable = true)
        r.error(recoverable = false)
        r.ended()

        assertEquals(listOf("submitted"), r.events)
    }

    @Test
    fun `a failed status is followed by the adapter's error`() {
        val r = Recorder()

        r.status(MeldStatus.FAILED)
        r.error(recoverable = false)

        assertEquals(listOf("status:failed", "error:x"), r.events)
    }

    @Test
    fun `an ended session with nothing seen reports an unknown outcome once`() {
        val r = Recorder()

        r.status(MeldStatus.PENDING)
        r.ended()
        r.ended()

        assertEquals(listOf("status:pending", "error:PAYMENT_OUTCOME_UNKNOWN"), r.events)
    }

    @Test
    fun `an ended session after a failed status reports a rejection once`() {
        val r = Recorder()

        r.status(MeldStatus.FAILED)
        r.ended()
        r.ended()

        assertEquals(listOf("status:failed", "error:PAYMENT_REJECTED"), r.events)
    }

    @Test
    fun `recoverable errors do not satisfy the backstop`() {
        val r = Recorder()

        r.error(recoverable = true)
        r.ended()

        assertEquals(listOf("error", "error:PAYMENT_OUTCOME_UNKNOWN"), r.events)
    }

    @Test
    fun `no backstop after the terminal callback`() {
        val r = Recorder()

        r.handlers.onCancel?.invoke("ord_1")
        r.ended()

        assertEquals(listOf("cancel"), r.events)
    }

    @Test
    fun `no backstop when the handle is unmounted`() {
        val r = Recorder()
        val session = object : MeldProviderSession {
            var unmounts = 0
            override fun unmount() {
                unmounts += 1
                r.handlers.sessionEnded?.invoke("ord_1")
            }
        }

        MeldWidgetHandle("test", session, r.gate).unmount()

        assertEquals(1, session.unmounts)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun `only the codes that say no attempt exists rule out an attempt`() {
        val codes = MeldErrorCode.entries.map { it.raw }
        assertEquals(
            listOf("PAYMENT_METHOD_UNAVAILABLE", "PRESENTATION_FAILED", "PAYMENT_REJECTED", "ORDER_STATE_CHANGED",
                "VERIFICATION_PENDING", "PAYMENT_OUTCOME_UNKNOWN", "PROVIDER_LOAD_FAILED"),
            codes,
        )
        assertEquals(listOf(false, false, false, false, false, true, true), codes.map(MeldErrorCode::attemptMayExist))
        for (code in listOf("PROVIDER_UNAVAILABLE", "APPLE_PAY_UNAVAILABLE", "authorize_session_failed", "LATER")) {
            assertTrue(code, MeldErrorCode.attemptMayExist(code))
        }
        assertFalse(MeldError("ord_1", MeldErrorCode.PAYMENT_REJECTED.raw, "x", null, false).attemptMayExist)
        assertTrue(MeldError("ord_1", MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN.raw, "x", null, false).attemptMayExist)
    }

    @Test
    fun `every error a mount reports carries the order's payment method`() {
        val errors = mutableListOf<MeldError>()
        val order = MeldOrder.fromMap(mapOf("id" to "ord_1", "paymentMethodType" to "GOOGLE_PAY"))
        val handlers = MeldEventHandlers(onError = { errors += it }).forMount(order, TerminalGate())

        handlers.onError?.invoke(MeldError("ord_1", MeldErrorCode.PROVIDER_LOAD_FAILED.raw, "offline", null, recoverable = true))
        handlers.sessionEnded?.invoke("ord_1")

        assertEquals(listOf("PROVIDER_LOAD_FAILED", "PAYMENT_OUTCOME_UNKNOWN"), errors.map { it.code })
        assertEquals(listOf("GOOGLE_PAY", "GOOGLE_PAY"), errors.map { it.paymentMethodType })

        val untyped = mutableListOf<MeldError>()
        MeldEventHandlers(onError = { untyped += it }).forMount(MeldOrder.fromMap(emptyMap()), TerminalGate())
            .onError?.invoke(MeldError("ord_1", "x", "x", null, recoverable = false))
        assertEquals(null, untyped.single().paymentMethodType)
    }

    @Test
    fun `a mount with no onError handler still treats an error as terminal`() {
        val events = mutableListOf<String>()
        val order = MeldOrder.fromMap(mapOf("paymentMethodType" to "GOOGLE_PAY"))
        val handlers = MeldEventHandlers(onCancel = { events += "cancel" }).forMount(order, TerminalGate())

        handlers.onError?.invoke(MeldError("ord_1", "x", "x", null, recoverable = false))
        handlers.onCancel?.invoke("ord_1")

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a backstop rejection uses the shared decline copy`() {
        val gate = TerminalGate()
        gate.observe(MeldStatus.FAILED)

        assertEquals(MeldErrorMessage.PAYMENT_REJECTED, gate.unannouncedEnd("ord_1")?.message)
    }
}
