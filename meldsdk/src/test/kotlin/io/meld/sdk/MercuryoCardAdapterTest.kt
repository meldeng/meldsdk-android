package io.meld.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure mapping logic — no Android framework, runs on the JVM. */
class MercuryoCardAdapterTest {

    @Test
    fun ready_maps_to_Ready() {
        val events = MercuryoCardAdapter.interpret(mapOf("type" to "mercuryoReady"), "order-1")
        assertEquals(listOf(MeldEvent.Ready), events)
    }

    @Test
    fun paymentFinished_maps_to_PaymentSubmitted() {
        val events = MercuryoCardAdapter.interpret(mapOf("type" to "mercuryoPaymentFinished"), "order-1")
        assertEquals(listOf(MeldEvent.PaymentSubmitted), events)
    }

    @Test
    fun unknown_type_maps_to_nothing() {
        assertTrue(MercuryoCardAdapter.interpret(mapOf("type" to "somethingElse"), "o").isEmpty())
        assertTrue(MercuryoCardAdapter.interpret(emptyMap(), "o").isEmpty())
    }

    @Test
    fun terminal_failed_emits_statusChange_and_error() {
        val events = MercuryoCardAdapter.interpret(
            mapOf("type" to "mercuryoStatusChanged", "data" to mapOf("status" to "failed")),
            "order-1",
        )
        assertEquals(2, events.size)
        val change = (events[0] as MeldEvent.StatusChange).change
        assertEquals(MeldStatus.FAILED, change.status)
        assertEquals("failed", change.providerStatus)
        val error = (events[1] as MeldEvent.Error).error
        assertEquals("failed", error.code)
        assertEquals(false, error.recoverable)
    }

    @Test
    fun terminal_cancelled_emits_statusChange_and_cancel() {
        val events = MercuryoCardAdapter.interpret(
            mapOf("type" to "mercuryoStatusChanged", "data" to mapOf("status" to "canceled")),
            "order-1",
        )
        assertEquals(2, events.size)
        assertEquals(MeldStatus.CANCELLED, (events[0] as MeldEvent.StatusChange).change.status)
        assertEquals(MeldEvent.Cancel, events[1])
    }

    @Test
    fun completed_status_emits_only_statusChange() {
        val events = MercuryoCardAdapter.interpret(
            mapOf("type" to "mercuryoStatusChanged", "data" to mapOf("status" to "paid")),
            "order-1",
        )
        assertEquals(1, events.size)
        assertEquals(MeldStatus.COMPLETED, (events[0] as MeldEvent.StatusChange).change.status)
    }

    @Test
    fun unknown_and_interim_codes_collapse_to_pending() {
        assertEquals(MeldStatus.PENDING, MercuryoCardAdapter.normalize(null))
        assertEquals(MeldStatus.PENDING, MercuryoCardAdapter.normalize(""))
        assertEquals(MeldStatus.PENDING, MercuryoCardAdapter.normalize("processing"))
        assertEquals(MeldStatus.PENDING, MercuryoCardAdapter.normalize("brand_new_code"))
        assertEquals(MeldStatus.COMPLETED, MercuryoCardAdapter.normalize("succeeded"))
        assertEquals(MeldStatus.FAILED, MercuryoCardAdapter.normalize("rejected"))
    }
}
