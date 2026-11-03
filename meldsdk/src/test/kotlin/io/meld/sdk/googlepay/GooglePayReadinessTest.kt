package io.meld.sdk.googlepay

import android.content.Context
import android.content.ContextWrapper
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldEnvironment.PRODUCTION
import io.meld.sdk.MeldEnvironment.SANDBOX
import io.meld.sdk.MeldErrorCode
import io.meld.sdk.MeldReadiness
import io.meld.sdk.toList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic Google Pay readiness check, with the Wallet call answered by a fake. */
class GooglePayReadinessTest {
    private val spec = GooglePayReadinessSpec(
        allowedAuthMethods = listOf("CRYPTOGRAM_3DS"),
        allowedCardNetworks = listOf("VISA", "MASTERCARD"),
        existingPaymentMethodRequired = true,
    )

    @Test
    fun `the request is apiVersion 2 with one card method and no tokenization`() {
        val request = JSONObject(spec.requestJson())

        assertEquals(2, request.getInt("apiVersion"))
        assertEquals(0, request.getInt("apiVersionMinor"))
        assertTrue(request.getBoolean("existingPaymentMethodRequired"))
        val methods = request.getJSONArray("allowedPaymentMethods")
        assertEquals(1, methods.length())
        val card = methods.getJSONObject(0)
        assertEquals("CARD", card.getString("type"))
        assertFalse(card.has("tokenizationSpecification"))
        val parameters = card.getJSONObject("parameters")
        assertEquals(listOf("CRYPTOGRAM_3DS"), parameters.getJSONArray("allowedAuthMethods").toList())
        assertEquals(listOf("VISA", "MASTERCARD"), parameters.getJSONArray("allowedCardNetworks").toList())

        val optional = JSONObject(spec.copy(existingPaymentMethodRequired = false).requestJson())
        assertFalse(optional.getBoolean("existingPaymentMethodRequired"))
    }

    @Test
    fun `ready is ready, whether or not the spec requires a card, in either environment`() {
        for (environment in MeldEnvironment.entries) {
            assertSame(MeldReadiness.Ready, googlePayReadiness(Result.success(true), spec, environment))
            val optional = spec.copy(existingPaymentMethodRequired = false)
            assertSame(MeldReadiness.Ready, googlePayReadiness(Result.success(true), optional, environment))
        }
    }

    @Test
    fun `not ready in production is unavailable, and the reason says whether a card was required`() {
        val noCard = googlePayReadiness(Result.success(false), spec, PRODUCTION) as MeldReadiness.Unavailable
        assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, noCard.code)
        assertEquals("Google Pay has no usable card on this device.", noCard.reason)

        val noWallet = googlePayReadiness(Result.success(false), spec.copy(existingPaymentMethodRequired = false), PRODUCTION)
            as MeldReadiness.Unavailable
        assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, noWallet.code)
        assertEquals("Google Pay is not supported on this device.", noWallet.reason)
    }

    @Test
    fun `no card in sandbox or qa is unknown, since the order may run Google's TEST environment`() {
        for (environment in listOf(SANDBOX, MeldEnvironment.QA)) {
            val noCard = googlePayReadiness(Result.success(false), spec, environment) as MeldReadiness.Unknown
            assertTrue(noCard.reason, noCard.reason.contains("TEST"))

            val noWallet = googlePayReadiness(Result.success(false), spec.copy(existingPaymentMethodRequired = false), environment)
            assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, (noWallet as MeldReadiness.Unavailable).code)
        }
    }

    @Test
    fun `a failed Wallet call is unknown`() {
        for (environment in MeldEnvironment.entries) {
            val answer = googlePayReadiness(Result.failure(IllegalStateException("17: API unavailable")), spec, environment)

            assertTrue((answer as MeldReadiness.Unknown).reason.contains("17: API unavailable"))
        }
    }

    @Test
    fun `the check sends the spec's request and maps the answer`() {
        val context: Context = ContextWrapper(null)
        val sent = mutableListOf<Pair<Context, String>>()
        val answers = mutableListOf<MeldReadiness>()
        var environment = PRODUCTION
        val client = GooglePayClient { ctx, request, onResult ->
            sent += ctx to request
            onResult(Result.success(false))
        }
        val readiness = GooglePayReadiness(client) { environment }

        readiness.check(context, spec, answers::add)
        environment = SANDBOX
        readiness.check(context, spec, answers::add)

        assertEquals(listOf(context to spec.requestJson(), context to spec.requestJson()), sent)
        assertEquals(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, (answers.first() as MeldReadiness.Unavailable).code)
        assertTrue(answers.last() is MeldReadiness.Unknown)
    }
}
