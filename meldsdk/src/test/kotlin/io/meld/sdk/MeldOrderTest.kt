package io.meld.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Order mapping via the pure `fromMap` path (no org.json / Android framework). */
class MeldOrderTest {

    @Test
    fun maps_top_level_and_detail_fields() {
        val order = MeldOrder.fromMap(
            mapOf(
                "id" to "order-1",
                "paymentMethodType" to "CREDIT_DEBIT_CARD",
                "paymentMethodResponseDetails" to mapOf(
                    "serviceProviderWidgetUrl" to "https://sandbox-widget.mrcr.io/?x=1",
                    "renderMode" to "IFRAME",
                    "sessionToken" to "abc123",
                ),
            ),
        )
        assertEquals("order-1", order.id)
        assertEquals("CREDIT_DEBIT_CARD", order.paymentMethodType)
        val details = order.paymentMethodResponseDetails!!
        assertEquals("https://sandbox-widget.mrcr.io/?x=1", details.serviceProviderWidgetUrl)
        assertEquals("IFRAME", details.renderMode)
        // Provider-specific fields stay reachable via raw / subscript.
        assertEquals("abc123", details["sessionToken"])
    }

    @Test
    fun missing_details_is_null_and_fields_are_nullable() {
        val order = MeldOrder.fromMap(mapOf("id" to "order-2"))
        assertEquals("order-2", order.id)
        assertNull(order.paymentMethodType)
        assertNull(order.paymentMethodResponseDetails)
    }

    @Test
    fun unsupported_order_is_not_embeddable() {
        val order = MeldOrder.fromMap(
            mapOf(
                "paymentMethodType" to "SOMETHING_ELSE",
                "paymentMethodResponseDetails" to mapOf("renderMode" to "REDIRECT"),
            ),
        )
        val caps = Meld.capabilities(order)
        assertEquals(false, caps.embeddable)
        assertEquals("unsupported", caps.surface)
    }

    @Test
    fun mercuryo_card_order_is_embeddable() {
        val order = MeldOrder.fromMap(
            mapOf(
                "paymentMethodType" to "CREDIT_DEBIT_CARD",
                "paymentMethodResponseDetails" to mapOf("renderMode" to "IFRAME"),
            ),
        )
        val caps = Meld.capabilities(order)
        assertTrue(caps.embeddable)
        assertEquals("embedded", caps.surface)
    }
}
