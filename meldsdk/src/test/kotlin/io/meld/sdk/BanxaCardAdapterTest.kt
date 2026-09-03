package io.meld.sdk

import android.view.ViewGroup
import io.meld.sdk.banxa.BanxaCheckoutPresenter
import io.meld.sdk.banxa.BanxaWebCheckoutPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two ways Banxa card support fails silently rather than loudly: registry dispatch (a
 * Banxa order being claimed by the Mercuryo catch-all) and the Banxa→Meld event mapping. Pure
 * logic — no Android framework, runs on the JVM.
 */
class BanxaCardAdapterTest {

    private fun order(
        serviceProvider: String? = "BANXA",
        paymentMethodType: String? = "CREDIT_DEBIT_CARD",
        renderMode: String? = "IFRAME",
        widgetUrl: String? = null,
        extra: Map<String, Any?> = mapOf("sdkSessionToken" to "primer-token"),
    ): MeldOrder {
        val details = buildMap<String, Any?> {
            putAll(extra)
            put("renderMode", renderMode)
            if (widgetUrl != null) put("serviceProviderWidgetUrl", widgetUrl)
        }
        return MeldOrder.fromMap(
            buildMap {
                put("id", "order-1")
                put("paymentMethodResponseDetails", details)
                if (paymentMethodType != null) put("paymentMethodType", paymentMethodType)
                if (serviceProvider != null) put("payload", mapOf("serviceProvider" to serviceProvider))
            },
        )
    }

    @Test
    fun meldOrder_parses_serviceProvider_from_payload() {
        assertEquals("BANXA", order().serviceProvider)
        assertEquals(null, order(serviceProvider = null).serviceProvider)
    }

    // MARK: - Registry dispatch

    @Test
    fun banxa_order_resolves_to_BanxaAdapter_not_the_Mercuryo_catchAll() {
        // The regression that matters: MercuryoCardAdapter matches any CREDIT_DEBIT_CARD + IFRAME
        // order, so without Banxa registered ahead of it a Banxa order lands in Mercuryo's adapter
        // and dies on the missing widget URL.
        assertTrue(Meld.adapterFor(order()) is BanxaCardAdapter)
    }

    @Test
    fun banxa_does_not_steal_a_mercuryo_order() {
        // Asserted against Banxa's matcher rather than through Meld.adapterFor: UpholdCardAdapter is
        // registered first and host-gates via android.net.Uri, which returns null under the plain-JVM
        // unit-test stubs — so adapterFor cannot be exercised here for an order carrying a widget URL.
        val mercuryo = order(
            serviceProvider = "MERCURYO",
            widgetUrl = "https://exchange.mercuryo.io/?widget_id=x",
            extra = emptyMap(),
        )
        assertFalse(BanxaCardAdapter().matches(mercuryo))
        assertTrue(MercuryoCardAdapter().matches(mercuryo))
    }

    @Test
    fun another_primer_backed_provider_is_not_claimed_by_Banxa() {
        // The reason dispatch keys on the provider rather than on the rendering technology: another
        // Primer-backed provider carries the same token shape and must NOT land in Banxa's adapter.
        assertFalse(BanxaCardAdapter().matches(order(serviceProvider = "STRIPE")))
    }

    @Test
    fun order_with_no_provider_is_not_claimed_by_Banxa() {
        // e.g. the mid-flow authorize-session response, which carries no payload.
        assertFalse(BanxaCardAdapter().matches(order(serviceProvider = null)))
    }

    @Test
    fun banxa_is_registered_ahead_of_the_mercuryo_catchAll() {
        // Ordering is the whole reason a Banxa order reaches its own adapter; assert it directly so a
        // future reshuffle of the registry fails here rather than at a customer's checkout.
        val banxaIndex = Meld.adapters.indexOfFirst { it is BanxaCardAdapter }
        val mercuryoIndex = Meld.adapters.indexOfFirst { it is MercuryoCardAdapter }
        assertTrue("BanxaCardAdapter is not registered", banxaIndex >= 0)
        assertTrue("BanxaCardAdapter must precede the Mercuryo catch-all", banxaIndex < mercuryoIndex)
    }

    @Test
    fun banxa_order_without_a_token_is_not_claimed() {
        // A Banxa CREDIT_DEBIT_CARD order with no sdkSessionToken is a client-created
        // (providerOrderCreation = CLIENT) order meant for Banxa's native SDK, which Android does not
        // integrate yet. Claiming it here used to fail at mount with "the backend did not receive a
        // token from Banxa" — a false diagnosis pointing at Banxa provisioning. Not matching lets it
        // fall through to the truthful "no adapter for this order".
        val tokenless = order(
            widgetUrl = "https://meld.banxa-sandbox.com/papi/transit/?initId=x",
            extra = emptyMap(),
        )
        assertFalse(BanxaCardAdapter().matches(tokenless))
        // (The un-gated Mercuryo CREDIT_DEBIT_CARD + IFRAME catch-all will still claim it — that is a
        // pre-existing property of the registry, not this adapter's to fix; it at least fails on a
        // missing widget URL rather than blaming Banxa provisioning.)
    }

    @Test
    fun applePay_order_is_not_claimed() {
        // Banxa serves Apple Pay from the same component and the same token as card, and the web SDK
        // presents it — but this adapter cannot. A wallet sheet in a WebView requires the page origin
        // to be one registered with the processor, and the bootstrap page's origin is Primer's, which
        // is not ours to register. Claiming the order would put a button on screen that can never open
        // a sheet. A native Primer session is the only route, and it is not built.
        assertFalse(BanxaCardAdapter().matches(order(paymentMethodType = "APPLE_PAY")))
    }

    @Test
    fun capabilities_report_embeddable() {
        assertTrue(Meld.capabilities(order()).embeddable)
    }

    // MARK: - Event mapping

    @Test
    fun ready_maps_to_Ready() {
        assertEquals(listOf(MeldEvent.Ready), BanxaWebCheckoutPresenter.interpret(mapOf("type" to "ready"), "order-1"))
    }

    @Test
    fun paymentSuccess_maps_to_PaymentSubmitted() {
        // UX hint only — settlement is confirmed from Banxa's webhook, not from this event.
        assertEquals(
            listOf(MeldEvent.PaymentSubmitted),
            BanxaWebCheckoutPresenter.interpret(mapOf("type" to "payment-success"), "order-1"),
        )
    }

    @Test
    fun paymentCancel_maps_to_Cancel() {
        assertEquals(
            listOf(MeldEvent.Cancel),
            BanxaWebCheckoutPresenter.interpret(mapOf("type" to "payment-cancel"), "order-1"),
        )
    }

    @Test
    fun paymentFailure_carries_primer_errorCode_and_message() {
        // Primer's payment-failure detail is {errorCode, errorMessage}, not {error:{code,message}}.
        val events = BanxaWebCheckoutPresenter.interpret(
            mapOf(
                "type" to "payment-failure",
                "detail" to mapOf("errorCode" to "card_declined", "errorMessage" to "Card was declined"),
            ),
            "order-1",
        )
        assertEquals(1, events.size)
        val error = (events[0] as MeldEvent.Error).error
        assertEquals("card_declined", error.code)
        assertEquals("Card was declined", error.message)
        assertEquals("order-1", error.orderId)
    }

    @Test
    fun cardError_is_not_surfaced_as_a_Meld_error() {
        // Inline field validation fires while the user types; surfacing it would spam onError.
        assertTrue(
            BanxaWebCheckoutPresenter.interpret(mapOf("type" to "card-error", "detail" to mapOf("errors" to emptyList<Any>())), "order-1")
                .isEmpty(),
        )
    }

    @Test
    fun events_without_a_Meld_equivalent_are_dropped() {
        assertTrue(BanxaWebCheckoutPresenter.interpret(mapOf("type" to "payment-start"), "order-1").isEmpty())
        assertTrue(BanxaWebCheckoutPresenter.interpret(mapOf("type" to "state-change"), "order-1").isEmpty())
    }

    // MARK: - Bootstrap

    @Test
    fun bootstrap_passes_token_as_a_property_and_escapes_it() {
        val html = BanxaWebCheckoutPresenter.bootstrapHtml("/*bundle*/", "tok\"</script>")
        assertTrue(html.contains("el.clientToken ="))
        // The token is JSON-encoded, so a quote or a </script> inside it cannot break out.
        assertFalse(html.contains("tok\"</script>"))
        assertTrue(html.contains("banxa-primer-checkout"))
    }

    // MARK: - Presenter seam

    /** Records what the adapter hands a presenter, so the seam can be tested without a WebView. */
    private class RecordingPresenter : BanxaCheckoutPresenter {
        override val capabilities: MeldCapabilities =
            MeldCapabilities(embeddable = false, surface = "recording", requiresUserGesture = true)
        var clientToken: String? = null
        var orderId: String? = null

        object Session : MeldProviderSession {
            override fun unmount() = Unit
        }

        override fun present(
            clientToken: String,
            orderId: String?,
            host: ViewGroup,
            handlers: MeldEventHandlers,
        ): MeldProviderSession {
            this.clientToken = clientToken
            this.orderId = orderId
            return Session
        }
    }

    /**
     * Unit tests run against android.jar stubs with `isReturnDefaultValues`, so no real ViewGroup can
     * be built. The seam never touches the host — it only passes it through — so a stub stands in.
     */
    private fun stubHost(): ViewGroup = object : ViewGroup(null) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    }

    @Test
    fun mount_delegates_token_and_orderId_to_the_injected_presenter() {
        // The seam that lets the Primer-native presenter replace the web one: selection, the token
        // guard and event mapping stay on the adapter, and only presentation is swapped.
        val presenter = RecordingPresenter()
        val session = BanxaCardAdapter(presenter).mount(order(), stubHost(), MeldEventHandlers())

        assertTrue(session === RecordingPresenter.Session)
        assertEquals("primer-token", presenter.clientToken)
        assertEquals("order-1", presenter.orderId)
    }

    @Test
    fun missing_token_is_rejected_before_any_presenter_runs() {
        // A provisioning fault must not reach a presenter — the native one would otherwise open a
        // Primer session with an empty token and fail somewhere far less legible.
        val presenter = RecordingPresenter()
        val thrown = runCatching {
            BanxaCardAdapter(presenter).mount(order(extra = emptyMap()), stubHost(), MeldEventHandlers())
        }.exceptionOrNull()

        assertTrue(thrown is MeldMountException.Unsupported)
        assertEquals(null, presenter.clientToken)
    }

    @Test
    fun default_presenter_is_the_web_component_so_existing_integrations_are_unchanged() {
        assertTrue(BanxaCardAdapter().presenter is BanxaWebCheckoutPresenter)
        assertEquals("embedded", BanxaCardAdapter().capabilities.surface)
        assertTrue(BanxaCardAdapter().capabilities.embeddable)
    }

    @Test
    fun capabilities_follow_the_presenter_rather_than_being_fixed_on_the_adapter() {
        // A native Primer sheet is not embeddable. An integrator guards on `embeddable`, so this has
        // to be the presenter's answer and not a constant that outlives the web component.
        val adapter = BanxaCardAdapter(RecordingPresenter())
        assertFalse(adapter.capabilities.embeddable)
        assertEquals("recording", adapter.capabilities.surface)
    }
}
