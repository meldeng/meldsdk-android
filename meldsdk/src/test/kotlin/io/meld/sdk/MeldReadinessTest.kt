package io.meld.sdk

import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import io.meld.sdk.hosted.HostedWalletLinkAdapter
import io.meld.sdk.hosted.VirtualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Readiness before an order exists: registry dispatch, the adapter default, and one bounded main-thread answer. */
class MeldReadinessTest {
    private val context: Context = ContextWrapper(null)
    private val clock = VirtualClock()
    private val presentation = MeldHeadlessPresentation("SYNTHETIC_SURFACE", "SYNTHETIC_PROTOCOL", 1)

    private class SyntheticAdapter(
        override val capabilities: MeldCapabilities = MeldCapabilities(true, "synthetic", false),
        override val presentations: List<AdapterPresentation> = emptyList(),
        private val answer: ((ReadinessQuery, (MeldReadiness) -> Unit) -> Unit)? = null,
    ) : MeldAdapter {
        override val label = "Synthetic"
        val queries = mutableListOf<ReadinessQuery>()

        override fun matches(paymentMethodType: String?, renderMode: String?, widgetUrl: String?) = false

        override fun mount(order: MeldOrder, host: ViewGroup, handlers: MeldEventHandlers): MeldProviderSession =
            error("Readiness must not mount")

        override fun readiness(query: ReadinessQuery, onResult: (MeldReadiness) -> Unit) {
            queries += query
            if (answer == null) super.readiness(query, onResult) else answer.invoke(query, onResult)
        }
    }

    private fun query(paymentMethodType: String = "CREDIT_DEBIT_CARD", presentation: MeldHeadlessPresentation = this.presentation) =
        ReadinessQuery(context, presentation, paymentMethodType)

    private fun ask(adapter: MeldAdapter?, query: ReadinessQuery = query()): List<MeldReadiness> {
        val answers = mutableListOf<MeldReadiness>()
        askReadiness(adapter, query, clock, TIMEOUT_MS) { answers += it }
        return answers
    }

    @Test
    fun `the default readiness is unknown unless the adapter cannot present the method`() {
        val noCheck = MeldReadiness.Unknown("This payment method has no device check; offer it and let the payment decide.")
        assertEquals(noCheck, capabilityReadiness(MeldCapabilities(true, "synthetic", false)))
        assertEquals(noCheck, capabilityReadiness(MeldCapabilities(false, "native-wallet", true)))
        val unsupported = capabilityReadiness(MeldCapabilities(false, "unsupported", false)) as MeldReadiness.Unavailable
        assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, unsupported.code)

        val answers = ask(SyntheticAdapter(capabilities = MeldCapabilities(false, "unsupported", false)))
        clock.advance()
        assertEquals(listOf<MeldReadiness>(unsupported), answers)
    }

    @Test
    fun `every built-in adapter without its own check answers from its capabilities`() {
        for (adapter in Meld.adapters.filterNot { it is HostedWalletLinkAdapter }) {
            for (key in adapter.presentations) {
                val answers = ask(adapter, query(key.paymentMethodType, key.presentation))
                clock.advance()
                assertEquals(adapter.label, listOf(capabilityReadiness(adapter.capabilities)), answers)
                assertTrue(adapter.label, answers.single() is MeldReadiness.Unknown)
            }
        }
    }

    @Test
    fun `the answer arrives on the scheduler, never inside the call`() {
        val answers = ask(SyntheticAdapter { _, onResult -> onResult(MeldReadiness.Ready) })

        assertTrue(answers.isEmpty())
        clock.advance()
        assertEquals(listOf<MeldReadiness>(MeldReadiness.Ready), answers)
    }

    @Test
    fun `an adapter that answers twice is delivered once`() {
        val answers = ask(SyntheticAdapter { _, onResult ->
            onResult(MeldReadiness.Ready)
            onResult(MeldReadiness.Unknown("second"))
        })
        clock.advance(TIMEOUT_MS * 2)

        assertEquals(listOf<MeldReadiness>(MeldReadiness.Ready), answers)
    }

    @Test
    fun `no answer within the bound is unknown, and a late answer is dropped`() {
        var late: ((MeldReadiness) -> Unit)? = null
        val answers = ask(SyntheticAdapter { _, onResult -> late = onResult })

        clock.advance(TIMEOUT_MS - 1)
        assertTrue(answers.isEmpty())
        clock.advance(1)
        assertTrue(answers.single() is MeldReadiness.Unknown)

        late!!(MeldReadiness.Ready)
        clock.advance()
        assertEquals(1, answers.size)
    }

    @Test
    fun `an adapter that throws is unknown, not unavailable`() {
        val answers = ask(SyntheticAdapter { _, _ -> throw IllegalStateException("boom") })
        clock.advance()
        val unknown = answers.single() as MeldReadiness.Unknown
        assertTrue(unknown.reason, unknown.reason.contains("boom"))
    }

    @Test
    fun `an adapter whose library is missing from the app is unknown, not a crash`() {
        val answers = ask(SyntheticAdapter { _, _ -> throw NoClassDefFoundError("com/google/android/gms/wallet/Wallet") })
        clock.advance()
        val unknown = answers.single() as MeldReadiness.Unknown
        assertTrue(unknown.reason, unknown.reason.contains("com/google/android/gms/wallet/Wallet"))
    }

    @Test
    fun `readiness is dispatched by the declared tuple, and an unknown tuple is unavailable`() {
        val card = SyntheticAdapter(presentations = listOf(AdapterPresentation("CREDIT_DEBIT_CARD", presentation)))
        val registry = MeldAdapterRegistry(Meld.adapters + card)

        val answers = ask(registry.adapter(presentation, "CREDIT_DEBIT_CARD"))
        clock.advance()
        assertTrue(answers.single() is MeldReadiness.Unknown)
        assertEquals(listOf(query()), card.queries)

        for ((method, declared) in listOf(
            "GOOGLE_PAY" to presentation,
            "CREDIT_DEBIT_CARD" to presentation.copy(version = 2),
            "CREDIT_DEBIT_CARD" to presentation.copy(surface = "PROVIDER_HOSTED"),
            "APPLE_PAY" to MeldHeadlessPresentation("PROVIDER_HOSTED", "COINBASE_PAYMENT_LINK", 1),
            "GOOGLE_PAY" to MeldHeadlessPresentation("PROVIDER_HOSTED", "COINBASE_PAYMENT_LINK", 2),
        )) {
            val unknown = ask(registry.adapter(declared, method), query(method, declared))
            clock.advance()
            val unavailable = unknown.single() as MeldReadiness.Unavailable
            assertEquals("$method $declared", MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, unavailable.code)
        }
        assertEquals(1, card.queries.size)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
