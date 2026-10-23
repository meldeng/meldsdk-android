package io.meld.sdk.hosted

import android.content.Context
import android.content.ContextWrapper
import io.meld.sdk.MeldAdapterRegistry
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldErrorCode
import io.meld.sdk.MeldHeadlessPresentation
import io.meld.sdk.MeldReadiness
import io.meld.sdk.ReadinessQuery
import io.meld.sdk.askReadiness
import io.meld.sdk.googlepay.GooglePayClient
import io.meld.sdk.googlepay.GooglePayReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hosted wallet adapter answers readiness from its profile's data, through the generic Google Pay check. */
class HostedWalletReadinessTest {
    private val context: Context = ContextWrapper(null)
    private val clock = VirtualClock()
    private val profile = HostedLinkProfiles.all.single { it.protocol == "COINBASE_PAYMENT_LINK" }
    private val googlePay = MeldHeadlessPresentation("PROVIDER_HOSTED", "COINBASE_PAYMENT_LINK", 1)

    private class FakeWallet(var answer: Result<Boolean>? = Result.success(true)) : GooglePayClient {
        val requests = mutableListOf<String>()
        var throws: Throwable? = null

        override fun isReadyToPay(context: Context, requestJson: String, onResult: (Result<Boolean>) -> Unit) {
            requests += requestJson
            throws?.let { throw it }
            answer?.let(onResult)
        }
    }

    private fun adapter(
        wallet: FakeWallet,
        platformSupported: Boolean = true,
        profiles: List<HostedLinkProfile> = HostedLinkProfiles.all,
        environment: MeldEnvironment = MeldEnvironment.PRODUCTION,
    ) = HostedWalletLinkAdapter(profiles, { platformSupported }, GooglePayReadiness(wallet) { environment })

    private fun ask(
        adapter: HostedWalletLinkAdapter,
        presentation: MeldHeadlessPresentation = googlePay,
        paymentMethodType: String = "GOOGLE_PAY",
    ): List<MeldReadiness> {
        val answers = mutableListOf<MeldReadiness>()
        val registered = MeldAdapterRegistry(listOf(adapter)).adapter(presentation, paymentMethodType)
        askReadiness(registered, ReadinessQuery(context, presentation, paymentMethodType), clock, 5_000) { answers += it }
        clock.advance()
        return answers
    }

    @Test
    fun `google pay asks the wallet with the profile's spec`() {
        val wallet = FakeWallet(Result.success(true))

        assertEquals(listOf<MeldReadiness>(MeldReadiness.Ready), ask(adapter(wallet)))
        assertEquals(listOf(profile.readiness.getValue("GOOGLE_PAY").requestJson()), wallet.requests)

        wallet.answer = Result.success(false)
        val unavailable = ask(adapter(wallet)).single() as MeldReadiness.Unavailable
        assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, unavailable.code)
        assertTrue(ask(adapter(wallet, environment = MeldEnvironment.SANDBOX)).single() is MeldReadiness.Unknown)
    }

    @Test
    fun `a WebView that cannot host the page is unavailable without asking the wallet`() {
        val wallet = FakeWallet()

        val answer = ask(adapter(wallet, platformSupported = false)).single() as MeldReadiness.Unavailable

        assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, answer.code)
        assertTrue(wallet.requests.isEmpty())
    }

    @Test
    fun `a method with no readiness spec falls back to the capability default`() {
        val wallet = FakeWallet()
        val bare = HostedLinkProfile(
            protocol = "SYNTHETIC_LINK",
            version = 1,
            hosts = setOf("pay.example.com"),
            channel = "synthetic",
            presentScripts = mapOf("GOOGLE_PAY" to ""),
            readiness = emptyMap(),
            events = { _, _ -> emptyList() },
        )
        val presentation = MeldHeadlessPresentation("PROVIDER_HOSTED", "SYNTHETIC_LINK", 1)

        assertTrue(ask(adapter(wallet, profiles = listOf(bare)), presentation = presentation).single() is MeldReadiness.Unknown)
        assertTrue(wallet.requests.isEmpty())
    }

    @Test
    fun `a wallet that throws, fails, is missing or never answers is unknown`() {
        val throwing = FakeWallet().apply { throws = IllegalArgumentException("bad request") }
        assertTrue(ask(adapter(throwing)).single() is MeldReadiness.Unknown)

        val excluded = FakeWallet().apply { throws = NoClassDefFoundError("com/google/android/gms/wallet/Wallet") }
        assertTrue(ask(adapter(excluded)).single() is MeldReadiness.Unknown)

        val failing = FakeWallet(Result.failure(IllegalStateException("8: internal error")))
        assertTrue(ask(adapter(failing)).single() is MeldReadiness.Unknown)

        val silent = FakeWallet(answer = null)
        val answers = ask(adapter(silent))
        assertTrue(answers.isEmpty())
        clock.advance(5_000)
        assertTrue(answers.single() is MeldReadiness.Unknown)
    }

    @Test
    fun `apple pay and other versions of the link are unavailable`() {
        val wallet = FakeWallet()
        for ((method, presentation) in listOf("APPLE_PAY" to googlePay, "GOOGLE_PAY" to googlePay.copy(version = 2))) {
            val answer = ask(adapter(wallet), presentation = presentation, paymentMethodType = method).single()
            assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, (answer as MeldReadiness.Unavailable).code)
        }
        assertTrue(wallet.requests.isEmpty())
    }
}
