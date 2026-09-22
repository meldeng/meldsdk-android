package io.meld.sdk

import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test

class PresentationRegistryTest {
    private val presentation = MeldHeadlessPresentation("EMBEDDED_WIDGET", "MERCURYO_WIDGET", 1)
    private fun descriptor(version: Any? = 1) = mapOf(
        "surface" to presentation.surface, "protocol" to presentation.protocol, "version" to version,
    )
    private fun order(declaration: Any?) = MeldOrder.fromMap(mapOf(
        "paymentMethodType" to "CREDIT_DEBIT_CARD", "headlessPresentation" to declaration,
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
                "protocol" to key.presentation.protocol, "version" to key.presentation.version))
            assertSame(adapter, Meld.adapterFor(declared))
            assertFalse(Meld.presentationCapabilities(key.presentation.copy(version = 2), key.paymentMethodType).embeddable)
            assertFalse(Meld.presentationCapabilities(key.presentation, "APPLE_PAY").embeddable)
        }
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
