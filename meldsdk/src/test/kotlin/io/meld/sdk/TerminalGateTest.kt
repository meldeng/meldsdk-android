package io.meld.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/** One onPaymentSubmitted per mount, whichever way the provider says the customer finished. */
class TerminalGateTest {

    private class Recorder {
        val events = mutableListOf<String>()
        val handlers = MeldEventHandlers(
            onPaymentSubmitted = { events.add("submitted") },
            onStatusChange = { events.add("status:${it.status.raw}") },
            onCancel = { events.add("cancel") },
            onError = { events.add("error") },
        ).gated()

        fun status(status: MeldStatus) =
            handlers.onStatusChange?.invoke(MeldStatusChange("ord_1", status, null, null))

        fun submitted() = handlers.onPaymentSubmitted?.invoke("ord_1")

        fun error(recoverable: Boolean) =
            handlers.onError?.invoke(MeldError("ord_1", "x", "x", null, recoverable))

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

    /** The same flow's later polling_success. The status still lands; the callback does not repeat. */
    @Test
    fun `a completed status after a submitted message does not refire`() {
        val r = Recorder()

        r.submitted()
        r.status(MeldStatus.COMPLETED)

        assertEquals(1, r.count("submitted"))
        assertEquals(1, r.count("status:completed"))
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
}
