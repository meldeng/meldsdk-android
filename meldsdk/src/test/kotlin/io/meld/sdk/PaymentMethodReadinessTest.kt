package io.meld.sdk

import android.content.Context
import android.content.ContextWrapper
import io.meld.sdk.googlepay.GooglePayClient
import io.meld.sdk.googlepay.GooglePayReadiness
import io.meld.sdk.googlepay.GooglePayReadinessSpec
import io.meld.sdk.hosted.VirtualClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device readiness before a quote: Google Pay asks for any card, Apple Pay is unavailable, the rest have no check. */
class PaymentMethodReadinessTest {
    private val context: Context = ContextWrapper(null)
    private val clock = VirtualClock()

    private class FakeWallet(var answer: Result<Boolean>? = Result.success(true)) : GooglePayClient {
        val requests = mutableListOf<String>()
        var throws: Throwable? = null

        override fun isReadyToPay(context: Context, requestJson: String, onResult: (Result<Boolean>) -> Unit) {
            requests += requestJson
            throws?.let { throw it }
            answer?.let(onResult)
        }
    }

    private fun ask(
        paymentMethodType: String,
        wallet: FakeWallet,
        environment: MeldEnvironment = MeldEnvironment.PRODUCTION,
    ): List<MeldReadiness> {
        val answers = mutableListOf<MeldReadiness>()
        DeviceReadiness(GooglePayReadiness(wallet) { environment }).ask(context, paymentMethodType, clock, TIMEOUT_MS) { answers += it }
        clock.advance()
        return answers
    }

    @Test
    fun `google pay asks for any card the customer has added`() {
        val request = JSONObject(GooglePayReadinessSpec.anyCard.requestJson())
        val card = request.getJSONArray("allowedPaymentMethods").getJSONObject(0)
        assertEquals("CARD", card.getString("type"))
        assertEquals(listOf("PAN_ONLY", "CRYPTOGRAM_3DS"), card.getJSONObject("parameters").getJSONArray("allowedAuthMethods").toList())
        assertEquals(
            listOf("VISA", "MASTERCARD", "AMEX", "DISCOVER"),
            card.getJSONObject("parameters").getJSONArray("allowedCardNetworks").toList(),
        )
        assertTrue(request.getBoolean("existingPaymentMethodRequired"))

        val wallet = FakeWallet(Result.success(true))
        assertEquals(listOf<MeldReadiness>(MeldReadiness.Ready), ask("GOOGLE_PAY", wallet))
        assertEquals(listOf(GooglePayReadinessSpec.anyCard.requestJson()), wallet.requests)
    }

    @Test
    fun `no card is unavailable in production and unknown in sandbox`() {
        val wallet = FakeWallet(Result.success(false))

        val unavailable = ask("GOOGLE_PAY", wallet).single() as MeldReadiness.Unavailable
        assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, unavailable.code)
        assertEquals("Google Pay has no usable card on this device.", unavailable.reason)
        assertTrue(ask("GOOGLE_PAY", wallet, MeldEnvironment.SANDBOX).single() is MeldReadiness.Unknown)
    }

    @Test
    fun `a wallet that fails, throws, is missing or never answers is unknown`() {
        assertTrue(ask("GOOGLE_PAY", FakeWallet(Result.failure(IllegalStateException("17")))).single() is MeldReadiness.Unknown)
        assertTrue(ask("GOOGLE_PAY", FakeWallet().apply { throws = IllegalStateException("boom") }).single() is MeldReadiness.Unknown)
        val excluded = FakeWallet().apply { throws = NoClassDefFoundError("com/google/android/gms/wallet/Wallet") }
        assertTrue(ask("GOOGLE_PAY", excluded).single() is MeldReadiness.Unknown)

        val answers = ask("GOOGLE_PAY", FakeWallet(answer = null))
        assertTrue(answers.isEmpty())
        clock.advance(TIMEOUT_MS)
        assertTrue(answers.single() is MeldReadiness.Unknown)
    }

    @Test
    fun `apple pay is unavailable without asking the wallet`() {
        val wallet = FakeWallet()

        val answer = ask("APPLE_PAY", wallet).single() as MeldReadiness.Unavailable

        assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, answer.code)
        assertEquals("Apple Pay is not available on Android.", answer.reason)
        assertTrue(wallet.requests.isEmpty())
    }

    @Test
    fun `a method with no device precondition is unknown without asking the wallet`() {
        val wallet = FakeWallet()
        for (method in listOf("CREDIT_DEBIT_CARD", "SEPA", "ACH", "google_pay")) {
            assertEquals(
                method,
                listOf(MeldReadiness.Unknown("This payment method has no device check; offer it and let the payment decide.")),
                ask(method, wallet),
            )
        }
        assertTrue(wallet.requests.isEmpty())
    }

    @Test
    fun `the answer arrives once, on the scheduler`() {
        val answers = mutableListOf<MeldReadiness>()
        val twice = GooglePayClient { _, _, onResult ->
            onResult(Result.success(true))
            onResult(Result.success(false))
        }

        DeviceReadiness(GooglePayReadiness(twice) { MeldEnvironment.PRODUCTION }).ask(context, "GOOGLE_PAY", clock, TIMEOUT_MS) { answers += it }
        assertTrue(answers.isEmpty())
        clock.advance(TIMEOUT_MS * 2)

        assertEquals(listOf<MeldReadiness>(MeldReadiness.Ready), answers)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
