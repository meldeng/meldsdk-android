package io.meld.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure mapping logic — no Android framework, runs on the JVM. */
class UpholdCardAdapterTest {

    @Test
    fun ready_maps_to_Ready() {
        assertEquals(listOf(MeldEvent.Ready), UpholdCardAdapter.interpret(mapOf("type" to "ready"), "order-1"))
    }

    @Test
    fun complete_maps_to_PaymentSubmitted() {
        // `complete` is a UX hint (user finished the flow), not settlement — settlement is a webhook.
        assertEquals(
            listOf(MeldEvent.PaymentSubmitted),
            UpholdCardAdapter.interpret(mapOf("type" to "complete"), "order-1"),
        )
    }

    @Test
    fun cancel_maps_to_Cancel() {
        assertEquals(listOf(MeldEvent.Cancel), UpholdCardAdapter.interpret(mapOf("type" to "cancel"), "order-1"))
    }

    @Test
    fun error_maps_to_Error_withMessageAndCode() {
        val events = UpholdCardAdapter.interpret(
            mapOf(
                "type" to "error",
                "detail" to mapOf("error" to mapOf("message" to "boom", "code" to "card-declined")),
            ),
            "order-1",
        )
        assertEquals(1, events.size)
        val error = (events[0] as MeldEvent.Error).error
        assertEquals("card-declined", error.code)
        assertEquals("boom", error.message)
        assertEquals(null, error.detail)
        assertEquals(false, error.recoverable)
    }

    @Test
    fun sdkUnavailable_maps_to_ProviderLoadFailed_withTheBootstrapCodeInDetail() {
        val events = UpholdCardAdapter.interpret(
            mapOf("type" to "error", "detail" to mapOf("error" to mapOf("code" to "sdk_unavailable", "message" to "gone"))),
            "order-1",
        )
        val error = (events.single() as MeldEvent.Error).error
        assertEquals("PROVIDER_LOAD_FAILED", error.code)
        assertEquals("uphold_card_failed:sdk_unavailable", error.detail)
        assertEquals("gone", error.message)
        assertTrue(error.attemptMayExist)
    }

    @Test
    fun mountFailed_maps_to_PresentationFailed_onBothSteps_withTheBootstrapCodeInDetail() {
        val message = mapOf("type" to "error", "detail" to mapOf("error" to mapOf("code" to "mount_failed", "message" to "threw")))
        val authorize = (UpholdCardAdapter.interpret(message, "order-1").single() as MeldEvent.Error).error
        val capture = (UpholdCardAdapter.interpretCapture(message).single() as MeldEvent.Error).error
        for (error in listOf(authorize, capture)) {
            assertEquals("PRESENTATION_FAILED", error.code)
            assertEquals("uphold_card_failed:mount_failed", error.detail)
            assertEquals("threw", error.message)
            assertFalse(error.attemptMayExist)
        }
        assertEquals("order-1", authorize.orderId)
    }

    @Test
    fun error_withoutDetail_stillEmitsError() {
        val events = UpholdCardAdapter.interpret(mapOf("type" to "error"), "order-1")
        assertEquals(1, events.size)
        assertTrue(events[0] is MeldEvent.Error)
    }

    @Test
    fun unknown_or_empty_maps_to_nothing() {
        assertTrue(UpholdCardAdapter.interpret(mapOf("type" to "somethingElse"), "o").isEmpty())
        assertTrue(UpholdCardAdapter.interpret(emptyMap(), "o").isEmpty())
    }

    // --- two-step helpers ---

    @Test
    fun extractCapturedCardId_readsSelectionId_fromValueOrDetail() {
        val flat = mapOf("type" to "complete", "value" to mapOf("selection" to mapOf("id" to "card-1")))
        assertEquals("card-1", UpholdCardAdapter.extractCapturedCardId(flat))

        val nested = mapOf("type" to "complete", "detail" to mapOf("value" to mapOf("selection" to mapOf("id" to "card-2"))))
        assertEquals("card-2", UpholdCardAdapter.extractCapturedCardId(nested))
    }

    @Test
    fun extractCapturedCardId_nullWhenNotCompleteOrNoSelection() {
        assertEquals(null, UpholdCardAdapter.extractCapturedCardId(mapOf("type" to "ready")))
        assertEquals(null, UpholdCardAdapter.extractCapturedCardId(mapOf("type" to "complete")))
        assertEquals(null, UpholdCardAdapter.extractCapturedCardId(emptyMap()))
    }

    @Test
    fun interpretCapture_surfacesReadyAndError_butNotCompleteOrCancel() {
        assertEquals(listOf(MeldEvent.Ready), UpholdCardAdapter.interpretCapture(mapOf("type" to "ready")))
        // A closed card dialog is not an abandoned checkout: Uphold's capture widget posts a bare
        // `cancel` after adding or deleting a card, and forwarding it tore the host's checkout down
        // mid-card-management. Authorize still forwards cancel — see interpret_* below.
        assertTrue(UpholdCardAdapter.interpretCapture(mapOf("type" to "cancel")).isEmpty())
        assertTrue(UpholdCardAdapter.interpretCapture(mapOf("type" to "error"))[0] is MeldEvent.Error)
        // capture 'complete' is handled out-of-band (card id extraction), not surfaced here
        assertTrue(UpholdCardAdapter.interpretCapture(mapOf("type" to "complete")).isEmpty())
    }

    // --- bootstrap (runs Uphold's web SDK from the session) ---

    @Test
    fun bootstrapHtml_embedsSession_andMountsUpholdWidget() {
        val html = UpholdCardAdapter.bootstrapHtml(
            bundleJs = "var x=1;",
            sessionUrl = "https://api.enterprise.sandbox.uphold.com/sessions/abc",
            token = "sess-token-123",
            flow = "select-for-deposit",
        )
        // Session {url, token, flow} is handed to Uphold's SDK.
        assertTrue(html.contains("https://api.enterprise.sandbox.uphold.com/sessions/abc"))
        assertTrue(html.contains("sess-token-123"))
        assertTrue(html.contains("select-for-deposit"))
        // Mounts via the vendored SDK, not a raw URL load.
        assertTrue(html.contains("MeldUpholdWidget.PaymentWidget"))
        assertTrue(html.contains("mountIframe"))
        assertTrue(html.contains("var x=1;")) // bundle inlined
        // Relays events through the injected native bridge.
        assertTrue(html.contains("window.meldSendToNativeApp"))
    }

    @Test
    fun bootstrapHtml_escapesClosingScriptInBundle() {
        val html = UpholdCardAdapter.bootstrapHtml(
            bundleJs = "console.log('</script>');",
            sessionUrl = "https://api.enterprise.sandbox.uphold.com/s",
            token = "t",
            flow = null,
        )
        // A literal </script> inside the bundle must not prematurely close the tag.
        assertTrue(html.contains("<\\/script"))
    }

    @Test
    fun upholdPaymentMethodsJs_cardTypesPreselectCard_othersFallBackToPicker() {
        val cardOnly = "[{type:'card'}]"
        val picker = "[{type:'card'},{type:'crypto'}]"
        assertEquals(cardOnly, UpholdCardAdapter.upholdPaymentMethodsJs("CREDIT_DEBIT_CARD"))
        assertEquals(cardOnly, UpholdCardAdapter.upholdPaymentMethodsJs("debit_card")) // case-insensitive
        assertEquals(cardOnly, UpholdCardAdapter.upholdPaymentMethodsJs("CARD"))
        assertEquals(picker, UpholdCardAdapter.upholdPaymentMethodsJs(null))
        assertEquals(picker, UpholdCardAdapter.upholdPaymentMethodsJs("BANK_TRANSFER"))
    }

    @Test
    fun bootstrapHtml_usesDerivedPaymentMethods_toPreselectCard() {
        val html = UpholdCardAdapter.bootstrapHtml(
            bundleJs = "var x=1;",
            sessionUrl = "https://api.enterprise.sandbox.uphold.com/s",
            token = "t",
            flow = null,
            paymentMethodsJs = "[{type:'card'}]",
        )
        // The widget is initialized with only card, so Uphold skips its "Select a payment method" screen.
        assertTrue(html.contains("paymentMethods:[{type:'card'}]"))
        assertFalse(html.contains("{type:'crypto'}"))
    }
}
