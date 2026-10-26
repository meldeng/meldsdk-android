package io.meld.sdk

import android.app.Activity
import android.view.ViewGroup
import io.meld.sdk.coinme.CoinmeCardAdapter
import io.meld.sdk.hosted.HostedWalletLinkAdapter
import org.junit.Assert.*
import org.junit.Test

class PresentationRegistryTest {
    private val presentation = MeldHeadlessPresentation("EMBEDDED_WIDGET", "MERCURYO_WIDGET", 1)
    private fun descriptor(version: Any? = 1) = mapOf(
        "surface" to presentation.surface, "protocol" to presentation.protocol, "version" to version,
    )
    private fun order(declaration: Any?, paymentMethodType: String = "CREDIT_DEBIT_CARD") = MeldOrder.fromMap(mapOf(
        "paymentMethodType" to paymentMethodType, "headlessPresentation" to declaration,
        "payload" to mapOf("serviceProvider" to "SYNTHETIC"),
        "paymentMethodResponseDetails" to mapOf("renderMode" to "IFRAME",
            "serviceProviderWidgetUrl" to "https://sandbox-exchange.mrcr.io/?widget_id=synthetic"),
    ))

    @Test fun integral_json_and_bridge_versions_select_the_declared_adapter() {
        for (version in listOf(1, 1L, 1.0, 1.0f)) {
            val decoded = order(descriptor(version))
            assertEquals(presentation, decoded.headlessPresentation)
            assertTrue(Meld.adapterFor(decoded) is MercuryoCardAdapter)
        }
        val json = MeldOrder.fromJson("""{"paymentMethodType":"CREDIT_DEBIT_CARD",
            "headlessPresentation":{"surface":"EMBEDDED_WIDGET","protocol":"MERCURYO_WIDGET","version":1}}""")
        assertEquals(presentation, json.headlessPresentation)
        assertTrue(Meld.capabilities(json).embeddable)
    }

    @Test fun malformed_and_unknown_declarations_never_use_legacy_fallback() {
        for (bad in listOf(null, emptyMap<String, Any>(), emptyList<Any>(), "MERCURYO_WIDGET",
            descriptor("1"), descriptor(1.5), descriptor(Double.NaN), descriptor(Long.MAX_VALUE),
            descriptor(-1), descriptor(0), descriptor(2),
            descriptor() + ("protocol" to "FUTURE"), descriptor() + ("surface" to "NATIVE_SDK"))) {
            assertNull(Meld.adapterFor(order(bad)))
            assertFalse(Meld.capabilities(order(bad)).embeddable)
        }
        assertNull(Meld.adapterFor(MeldOrder.fromJson("""{"paymentMethodType":"CREDIT_DEBIT_CARD",
            "headlessPresentation":null,"paymentMethodResponseDetails":{"renderMode":"IFRAME",
            "serviceProviderWidgetUrl":"https://sandbox-exchange.mrcr.io/"}}""")))
    }

    @Test fun all_builtin_registrations_share_precreate_and_order_resolution() {
        for (adapter in Meld.adapters) for (key in adapter.presentations) {
            assertEquals(adapter.capabilities, Meld.presentationCapabilities(key.presentation, key.paymentMethodType))
            val declared = order(mapOf("surface" to key.presentation.surface,
                "protocol" to key.presentation.protocol, "version" to key.presentation.version), key.paymentMethodType)
            // A Coinme order needs its session data and payment actions as well; CoinmeOrderTest covers it.
            assertSame(adapter.takeUnless { it is CoinmeCardAdapter }, Meld.adapterFor(declared))
            assertFalse(Meld.presentationCapabilities(key.presentation.copy(version = 2), key.paymentMethodType).embeddable)
            assertFalse(Meld.presentationCapabilities(key.presentation, "APPLE_PAY").embeddable)
        }
    }

    @Test fun google_pay_payment_link_resolves_to_the_hosted_wallet_adapter_and_apple_pay_stays_unsupported() {
        val link = mapOf("surface" to "PROVIDER_HOSTED", "protocol" to "COINBASE_PAYMENT_LINK", "version" to 1)
        val presentation = MeldHeadlessPresentation("PROVIDER_HOSTED", "COINBASE_PAYMENT_LINK", 1)
        val googlePay = order(link, "GOOGLE_PAY")
        assertEquals(presentation, googlePay.headlessPresentation)
        assertTrue(Meld.adapterFor(googlePay) is HostedWalletLinkAdapter)
        assertEquals(MeldCapabilities(false, "native-wallet", true),
            HostedWalletLinkAdapter(platformSupported = { true }).capabilities)
        assertEquals(MeldCapabilities(false, "unsupported", false),
            HostedWalletLinkAdapter(platformSupported = { false }).capabilities)

        for (unsupported in listOf(order(link, "APPLE_PAY"), order(link + ("version" to 2), "GOOGLE_PAY"),
            order(link + ("protocol" to "COINBASE_APPLE_PAY"), "APPLE_PAY"),
            order(link + ("protocol" to "COINBASE_APPLE_PAY"), "GOOGLE_PAY"),
            order(link + ("surface" to "EMBEDDED_WIDGET"), "GOOGLE_PAY"))) {
            assertNull(Meld.adapterFor(unsupported))
            assertEquals("unsupported", Meld.capabilities(unsupported).surface)
        }
        assertEquals("unsupported", Meld.presentationCapabilities(presentation, "APPLE_PAY").surface)
        assertNull(Meld.adapterFor(MeldOrder.fromMap(mapOf("paymentMethodType" to "GOOGLE_PAY",
            "paymentMethodResponseDetails" to mapOf("paymentLinkUrl" to "https://pay.coinbase.com/buy")))))
    }

    @Test fun hosted_wallet_mount_refuses_what_it_cannot_present_before_touching_a_view() {
        val host = object : ViewGroup(null) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = Unit
        }
        val adapter = HostedWalletLinkAdapter(platformSupported = { true })
        val declared = mapOf("surface" to "PROVIDER_HOSTED", "protocol" to "COINBASE_PAYMENT_LINK", "version" to 1)
        fun googlePay(details: Map<String, Any?>) = MeldOrder.fromMap(mapOf("id" to "o1",
            "paymentMethodType" to "GOOGLE_PAY", "headlessPresentation" to declared,
            "paymentMethodResponseDetails" to details))

        assertThrows(MeldMountException.MissingWidgetUrl::class.java) {
            adapter.mount(googlePay(emptyMap()), host, MeldEventHandlers())
        }
        for (url in listOf("https://untrusted.example/", "http://pay.coinbase.com/", "https://x.pay.coinbase.com/")) {
            val error = assertThrows(MeldMountException.Unsupported::class.java) {
                adapter.mount(googlePay(mapOf("paymentLinkUrl" to url)), host, MeldEventHandlers())
            }
            assertEquals("Invalid widget origin for declared presentation.", error.message)
        }
        val payable = googlePay(mapOf("surface" to mapOf("url" to "https://pay.coinbase.com/buy")))
        assertThrows(MeldMountException.Unsupported::class.java) {
            HostedWalletLinkAdapter(platformSupported = { false }).mount(payable, host, MeldEventHandlers())
        }
        assertThrows(MeldMountException.PresentationUnavailable::class.java) {
            adapter.mount(payable, host, MeldEventHandlers())
        }
        assertThrows(MeldMountException.MissingWidgetUrl::class.java) {
            adapter.mount(googlePay(emptyMap()), Activity(), MeldEventHandlers())
        }
        assertThrows(MeldMountException.PresentationUnavailable::class.java) {
            adapter.mount(payable, Activity(), MeldEventHandlers())
        }
    }

    @Test fun an_activity_mount_refuses_an_order_that_renders_into_a_view() {
        // A Coinme order needs its session data and payment actions as well; CoinmeOrderTest covers it.
        for (adapter in Meld.adapters.filter { it.capabilities.embeddable && it !is CoinmeCardAdapter }) for (key in adapter.presentations) {
            val declared = order(mapOf("surface" to key.presentation.surface,
                "protocol" to key.presentation.protocol, "version" to key.presentation.version), key.paymentMethodType)
            val error = assertThrows(MeldMountException.MissingHost::class.java) { Meld.mount(declared, Activity()) }
            assertTrue(error.message!!, error.message!!.startsWith(adapter.label))
        }
        assertThrows(MeldMountException.Unsupported::class.java) { Meld.mount(order(descriptor(2)), Activity()) }
    }

    @Test fun synthetic_adapter_registration_needs_no_shared_dispatch_changes_and_rejects_duplicates() {
        val synthetic = object : MeldAdapter {
            override val label = "Synthetic"
            override val capabilities = MeldCapabilities(true, "synthetic", false)
            override val presentations = listOf(AdapterPresentation("CREDIT_DEBIT_CARD",
                presentation.copy(protocol = "SYNTHETIC_PROTOCOL")))
            override fun matches(paymentMethodType: String?, renderMode: String?, widgetUrl: String?) = false
            override fun mount(order: MeldOrder, host: ViewGroup, handlers: MeldEventHandlers): MeldProviderSession =
                error("Pure registry test must not mount")
        }
        val registry = MeldAdapterRegistry(Meld.adapters + synthetic)
        assertSame(synthetic, registry.adapter(order(descriptor() + ("protocol" to "SYNTHETIC_PROTOCOL"))))
        assertThrows(IllegalArgumentException::class.java) { MeldAdapterRegistry(listOf(synthetic, synthetic)) }
    }

    @Test fun legacy_selection_requires_recognized_https_origins() {
        for ((url, supported) in listOf("https://sandbox-exchange.mrcr.io/" to true,
            "https://payment-widget.enterprise.uphold.com/" to true,
            "https://unknown.example/" to false, "http://sandbox-exchange.mrcr.io/" to false,
            "https://user:secret@sandbox-exchange.mrcr.io/" to false,
            "https://sandbox-exchange.mrcr.io.evil.example/" to false,
            "https://sandbox-exchange.mrcr.io:444/" to false)) {
            val legacy = MeldOrder.fromMap(mapOf("paymentMethodType" to "CREDIT_DEBIT_CARD",
                "paymentMethodResponseDetails" to mapOf("renderMode" to "IFRAME", "serviceProviderWidgetUrl" to url)))
            assertEquals(url, supported, Meld.capabilities(legacy).embeddable)
        }
    }
    @Test fun unsupported_declarations_and_untrusted_widget_origins_fail_before_mount() {
        val host = object : ViewGroup(null) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = Unit
        }
        assertThrows(MeldMountException.Unsupported::class.java) { Meld.mount(order(descriptor(2)), host) }
        for (protocol in listOf("MERCURYO_WIDGET", "UPHOLD_WIDGET")) {
            val invalid = MeldOrder.fromMap(mapOf("paymentMethodType" to "CREDIT_DEBIT_CARD",
                "headlessPresentation" to (descriptor() + ("protocol" to protocol)),
                "paymentMethodResponseDetails" to mapOf("serviceProviderWidgetUrl" to "https://untrusted.example/")))
            val error = assertThrows(MeldMountException.Unsupported::class.java) { Meld.mount(invalid, host) }
            assertEquals("Invalid widget origin for declared presentation.", error.message)
        }
    }

}
