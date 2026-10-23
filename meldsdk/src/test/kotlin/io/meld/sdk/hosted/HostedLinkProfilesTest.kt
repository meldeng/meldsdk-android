package io.meld.sdk.hosted

import io.meld.sdk.MeldErrorMessage
import io.meld.sdk.MeldEvent
import io.meld.sdk.MeldOrder
import io.meld.sdk.MeldStatus
import io.meld.sdk.googlepay.GooglePayReadinessSpec
import io.meld.sdk.isRegisteredWidgetUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The declared hosted-link protocols: what each page says, mapped to Meld events. Pure; no page is loaded. */
class HostedLinkProfilesTest {

    private val profile = HostedLinkProfiles.all.single { it.protocol == "COINBASE_PAYMENT_LINK" }

    private fun events(eventName: String, data: String = "{}"): List<MeldEvent> {
        val body = """{"eventName":"$eventName","data":$data}"""
        return profile.interpret(mapOf("channel" to "androidWebView", "body" to body), "o1")
    }

    @Test
    fun `the payment link protocol is version 1, pinned to one exact host, and presents only Google Pay`() {
        assertEquals(1, profile.version)
        assertEquals(setOf("pay.coinbase.com"), profile.hosts)
        assertEquals(setOf("https://pay.coinbase.com"), profile.origins)
        assertEquals("androidWebView", profile.channel)
        assertEquals(setOf("GOOGLE_PAY"), profile.presentScripts.keys)
    }

    @Test
    fun `google pay readiness asks what the page's card-required request asks, and only for presented methods`() {
        val captured = JSONObject(javaClass.getResource("coinbase-payment-link-v1-payment-requests.json")!!.readText())
            .getJSONArray("requests").objects()
            .filter { it.getString("supportedMethods") == "https://google.com/pay" }
        fun GooglePayReadinessSpec.asks(request: JSONObject): Boolean {
            val card = request.getJSONArray("allowedPaymentMethods").objects().single { it.getString("type") == "CARD" }
            return card.getJSONArray("authMethods").strings() == allowedAuthMethods &&
                card.getJSONArray("networks").strings() == allowedCardNetworks
        }
        val spec = profile.readiness.getValue("GOOGLE_PAY")

        assertTrue(spec.existingPaymentMethodRequired)
        assertTrue(spec.asks(captured.single { it.optBoolean("existingPaymentMethodRequired") }))
        assertTrue(captured.filter { it.has("merchantName") }.all { spec.asks(it) })
        for (each in HostedLinkProfiles.all) {
            assertTrue(each.protocol, each.presentScripts.keys.containsAll(each.readiness.keys))
        }
    }

    @Test
    fun `the press script clicks the page's button once and says whether it was there`() {
        val script = profile.presentScripts.getValue("GOOGLE_PAY")
        assertTrue(script.contains("document.getElementById('gpay-button-online-api-id')"))
        assertEquals(1, Regex("""\.click\(\)""").findAll(script).count())
        assertTrue(script.contains("return 'missing'"))
        assertTrue(script.contains("return 'clicked'"))
    }

    @Test
    fun `load success is ready, and nothing else is`() {
        assertEquals(listOf(MeldEvent.Ready), events("onramp_api.load_success"))
        for (name in listOf("onramp_api.load_pending", "onramp_api.commit_success", "onramp_api.polling_start",
            "onramp_api.polling_success", "onramp_api.cancel")) {
            assertFalse(name, events(name).contains(MeldEvent.Ready))
        }
    }

    @Test
    fun `commit success and polling start are pending then submitted`() {
        for (name in listOf("onramp_api.commit_success", "onramp_api.polling_start")) {
            val mapped = events(name)
            assertEquals(name, 2, mapped.size)
            val change = (mapped.first() as MeldEvent.StatusChange).change
            assertEquals(MeldStatus.PENDING, change.status)
            assertEquals(name, change.providerStatus)
            assertEquals("o1", change.orderId)
            assertEquals(MeldEvent.PaymentSubmitted, mapped.last())
        }
    }

    @Test
    fun `polling success is a completed status`() {
        val change = (events("onramp_api.polling_success").single() as MeldEvent.StatusChange).change
        assertEquals(MeldStatus.COMPLETED, change.status)
        assertEquals("onramp_api.polling_success", change.providerStatus)
    }

    @Test
    fun `cancel is cancel`() {
        assertEquals(listOf(MeldEvent.Cancel), events("onramp_api.cancel"))
    }

    @Test
    fun `every page error maps to a normalized terminal code with the provider's in detail`() {
        val unsupported = "ERROR_CODE_GUEST_GOOGLE_PAY_NOT_SUPPORTED"
        for ((event, providerCode, code) in listOf(
            Triple("onramp_api.load_error", unsupported, "PAYMENT_METHOD_UNAVAILABLE"),
            Triple("onramp_api.commit_error", unsupported, "PAYMENT_METHOD_UNAVAILABLE"),
            Triple("onramp_api.load_error", "ERROR_CODE_INIT", "ORDER_STATE_CHANGED"),
            Triple("onramp_api.load_error", "ERROR_CODE_SOMETHING_ELSE", "PRESENTATION_FAILED"),
            Triple("onramp_api.load_error", null, "PRESENTATION_FAILED"),
            Triple("onramp_api.commit_error", "ERROR_CODE_DECLINED", "PAYMENT_REJECTED"),
            Triple("onramp_api.polling_error", null, "PAYMENT_OUTCOME_UNKNOWN"),
            Triple("onramp_api.polling_error", "ERROR_CODE_TIMEOUT", "PAYMENT_OUTCOME_UNKNOWN"),
        )) {
            val row = "$event/$providerCode"
            val data = providerCode?.let { """{"errorCode":"$it"}""" } ?: "{}"
            val error = (events(event, data).single() as MeldEvent.Error).error
            assertEquals(row, code, error.code)
            assertEquals(row, "o1", error.orderId)
            assertFalse(row, error.recoverable)
            assertEquals(row, providerCode?.let { "$event:$it" } ?: event, error.detail)
            assertEquals(row, code == "PAYMENT_OUTCOME_UNKNOWN", error.attemptMayExist)
        }
    }

    @Test
    fun `a decline uses the shared copy, other errors the page's own message`() {
        val declined = (events("onramp_api.commit_error", """{"errorCode":"X","errorMessage":"card declined"}""")
            .single() as MeldEvent.Error).error
        assertEquals(MeldErrorMessage.PAYMENT_REJECTED, declined.message)

        val failed = (events("onramp_api.load_error", """{"errorMessage":"page unavailable"}""").single()
            as MeldEvent.Error).error
        assertEquals("page unavailable", failed.message)

        val bare = (events("onramp_api.load_error").single() as MeldEvent.Error).error
        assertEquals("The provider reported an error.", bare.message)
    }

    @Test
    fun `unmapped events, malformed bodies and other channels map to nothing`() {
        assertTrue(events("onramp_api.load_pending").isEmpty())
        assertTrue(events("onramp_api.something_new").isEmpty())
        assertTrue(profile.interpret(mapOf("channel" to "androidWebView", "body" to "not json"), "o1").isEmpty())
        assertTrue(profile.interpret(mapOf("channel" to "androidWebView", "body" to "{}"), "o1").isEmpty())
        assertTrue(profile.interpret(mapOf("channel" to "androidWebView"), "o1").isEmpty())
        val elsewhere = mapOf("channel" to "otherChannel", "body" to """{"eventName":"onramp_api.load_success"}""")
        assertTrue(profile.interpret(elsewhere, "o1").isEmpty())
        assertFalse(profile.isProviderMessage(elsewhere))
        assertFalse(profile.isProviderMessage(mapOf("type" to "window message")))
        assertTrue(profile.isProviderMessage(mapOf("channel" to "androidWebView")))
    }

    @Test
    fun `the link comes from surface url, else paymentLinkUrl, pinned to the profile's exact host`() {
        fun order(details: Map<String, Any?>) = MeldOrder.fromMap(mapOf("paymentMethodResponseDetails" to details))
        val link = "https://pay.coinbase.com/buy/x?useGooglePaySandbox=true"

        assertEquals(link, HostedWalletLinkAdapter.paymentLink(order(mapOf("surface" to mapOf("url" to link),
            "paymentLinkUrl" to "https://pay.coinbase.com/other"))))
        assertEquals(link, HostedWalletLinkAdapter.paymentLink(order(mapOf("surface" to mapOf("url" to ""),
            "paymentLinkUrl" to link))))
        assertNull(HostedWalletLinkAdapter.paymentLink(order(mapOf("paymentLinkUrl" to ""))))
        assertNull(HostedWalletLinkAdapter.paymentLink(MeldOrder.fromMap(emptyMap())))

        for ((url, pinned) in listOf(link to true, "https://coinbase.com/buy" to false,
            "https://evil.pay.coinbase.com/" to false, "http://pay.coinbase.com/" to false,
            "https://pay.coinbase.com.evil.test/" to false)) {
            assertEquals(url, pinned, isRegisteredWidgetUrl(url, profile.origins))
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
