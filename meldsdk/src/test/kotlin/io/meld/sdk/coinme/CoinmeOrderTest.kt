package io.meld.sdk.coinme

import android.app.Activity
import android.view.ViewGroup
import io.meld.sdk.Meld
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldHeadlessPresentation
import io.meld.sdk.MeldMountException
import io.meld.sdk.MeldOrder
import io.meld.sdk.coinme.CoinmeFixtures.op
import io.meld.sdk.coinme.CoinmeFixtures.orderMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinmeOrderTest {
    private fun parse(map: Map<String, Any?>, environment: MeldEnvironment = MeldEnvironment.SANDBOX) =
        CoinmeOrder.parse(MeldOrder.fromMap(map), environment)

    @Test
    fun `a declared Coinme card order resolves to the Coinme adapter`() {
        val order = CoinmeFixtures.order()

        assertTrue(Meld.adapterFor(order) is CoinmeCardAdapter)
        assertEquals("embedded", Meld.capabilities(order).surface)
        assertTrue(
            Meld.presentationCapabilities(MeldHeadlessPresentation("VENDOR_SDK", "COINME_VAULT", 1), "CREDIT_DEBIT_CARD")
                .embeddable,
        )
        assertThrows(MeldMountException.MissingHost::class.java) { Meld.mount(order, Activity()) }
        val coinme = CoinmeFixtures.coinme()
        assertEquals(CoinmeFixtures.ORDER_ID, coinme.id)
        assertFalse(coinme.live)
        assertFalse(coinme.toString().contains(CoinmeFixtures.BROWSER_TOKEN))
    }

    @Test
    fun `both card operations must be declared with idempotency keys`() {
        val host = object : ViewGroup(null) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        }
        for (operations in listOf(
            listOf(op("CREATE_PAYMENT_SESSION", true)),
            listOf(op("CREATE_CUSTOMER_AUTH_TOKEN", true)),
            listOf(op("CREATE_CUSTOMER_AUTH_TOKEN", false), op("CREATE_PAYMENT_SESSION", true)),
            listOf(op("CREATE_CUSTOMER_AUTH_TOKEN", true), op("CREATE_PAYMENT_SESSION", false)),
        )) {
            val order = MeldOrder.fromMap(orderMap(operations = operations))
            assertNull(Meld.adapterFor(order))
            assertEquals("unsupported", Meld.capabilities(order).surface)
            try {
                Meld.mount(order, host)
                throw AssertionError("expected Unsupported")
            } catch (_: MeldMountException.Unsupported) {
            }
        }
        val extra = CoinmeFixtures.OPERATIONS + op("READ_SUBMISSION", false)
        assertNotNull(parse(orderMap(operations = extra)))
    }

    @Test
    fun `only VENDOR_SDK COINME_VAULT version 1 for a card is admitted`() {
        val declared = mapOf("surface" to "VENDOR_SDK", "protocol" to "COINME_VAULT", "version" to 1)
        for (presentation in listOf(declared + ("version" to 2), declared + ("protocol" to "COINME_WIDGET"),
            declared + ("surface" to "NATIVE_SDK"))) {
            assertNull(Meld.adapterFor(MeldOrder.fromMap(orderMap(presentation = presentation))))
        }
        assertNull(Meld.adapterFor(MeldOrder.fromMap(orderMap(paymentMethodType = "APPLE_PAY"))))
        assertNull(parse(orderMap(paymentMethodType = "APPLE_PAY")))
    }

    @Test
    fun `the session data the flow needs is required`() {
        for (key in listOf("customerId", "paymentProviderId", "riskClientId", "riskPartnerId", "environment")) {
            assertNull(key, parse(orderMap(sessionData = { it.remove(key) })))
            assertNull(key, parse(orderMap(sessionData = { it[key] = " " })))
        }
        assertNull(parse(orderMap(sessionData = { it["environment"] = "production" })))
        assertNull(parse(orderMap(sessionData = { it["savedCards"] = "1111" })))
        for (key in listOf("sdkSessionToken", "sdkSessionData", "continuationToken")) {
            assertNull(key, parse(orderMap(details = { it.remove(key) })))
        }
        assertNull(parse(orderMap() - "paymentActions"))
    }

    @Test
    fun `the order's Coinme environment must match the configured one`() {
        val live = orderMap(environment = "live")

        assertNull(parse(live, MeldEnvironment.SANDBOX))
        assertNull(parse(live, MeldEnvironment.QA))
        assertTrue(parse(live, MeldEnvironment.PRODUCTION)!!.live)
        assertNull(parse(orderMap(), MeldEnvironment.PRODUCTION))
        val qa = parse(orderMap(), MeldEnvironment.QA)!!
        assertFalse(qa.live)
        assertEquals("api-qa.meld.io", qa.actions.endpoint.host)
    }
}
