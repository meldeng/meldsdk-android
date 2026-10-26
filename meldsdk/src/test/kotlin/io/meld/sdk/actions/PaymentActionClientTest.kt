package io.meld.sdk.actions

import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class PaymentActionClientTest {
    private val endpoint = "/crypto/order/headless/onramp/COINME/ord_1/actions"
    private val pointer = "/paymentMethodResponseDetails/continuationToken"

    private fun op(name: Any?, required: Any?) = mapOf("operation" to name, "idempotencyKeyRequired" to required)

    private fun actions(
        endpoint: Any? = this.endpoint,
        pointer: Any? = this.pointer,
        operations: Any? = listOf(op("CREATE_PAYMENT_SESSION", true), op("READ_STATUS", false)),
        version: Any? = 1,
    ) = mapOf("version" to version, "endpoint" to endpoint, "bearerTokenPointer" to pointer, "operations" to operations)

    private fun order(
        actions: Map<String, Any?> = actions(),
        id: String? = "ord_1",
        provider: String? = "COINME",
        token: Any? = "jwt.bearer.token",
    ) = MeldOrder.fromMap(mapOf(
        "id" to id,
        "payload" to mapOf("serviceProvider" to provider),
        "paymentMethodResponseDetails" to mapOf("continuationToken" to token),
        "paymentActions" to actions,
    ))

    private fun descriptor(order: MeldOrder = order(), environment: MeldEnvironment = MeldEnvironment.SANDBOX) =
        PaymentActionDescriptor.parse(order, environment)

    private fun client(transport: PaymentActionTransport) = PaymentActionClient(descriptor()!!, transport)

    @Test
    fun `a declared endpoint on the environment's API host carries the order's bearer`() {
        val key = UUID.randomUUID()
        val descriptor = descriptor()!!
        val request = descriptor.request("CREATE_PAYMENT_SESSION", mapOf("paymentToken" to "pm_1"), key)

        assertEquals("https://api-sb.meld.io$endpoint", request.url.toString())
        assertEquals("Bearer jwt.bearer.token", request.headers["Authorization"])
        assertEquals(key.toString(), request.headers["X-Idempotency-Key"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("no-store", request.headers["Cache-Control"])
        val body = JSONObject(String(request.body))
        assertEquals(1, body.getInt("version"))
        assertEquals("CREATE_PAYMENT_SESSION", body.getString("operation"))
        assertEquals("pm_1", body.getString("paymentToken"))
        assertFalse(descriptor.toString().contains("jwt"))
        assertFalse(request.toString().contains("jwt"))
    }

    @Test
    fun `an idempotency key goes only with an operation that declares one`() {
        val descriptor = descriptor()!!
        val key = UUID.randomUUID()

        assertNull(descriptor.request("READ_STATUS", emptyMap(), null).headers["X-Idempotency-Key"])
        for (refused in listOf<() -> Unit>(
            { descriptor.request("READ_STATUS", emptyMap(), key) },
            { descriptor.request("CREATE_PAYMENT_SESSION", emptyMap(), null) },
            { descriptor.request("CREATE_CUSTOMER_AUTH_TOKEN", emptyMap(), key) },
            { descriptor.request("CREATE_PAYMENT_SESSION", mapOf("version" to 2), key) },
            { descriptor.request("CREATE_PAYMENT_SESSION", mapOf("operation" to "READ_STATUS"), key) },
        )) {
            try {
                refused()
                throw AssertionError("expected InvalidRequest")
            } catch (_: PaymentActionError.InvalidRequest) {
            }
        }
    }

    @Test
    fun `each environment has its own API host`() {
        assertEquals("https://api-qa.meld.io$endpoint", descriptor(environment = MeldEnvironment.QA)!!.endpoint.toString())
        assertEquals("https://api.meld.io$endpoint", descriptor(environment = MeldEnvironment.PRODUCTION)!!.endpoint.toString())

        val absolute = order(actions(endpoint = "https://api-sb.meld.io$endpoint"))
        assertNotNull(descriptor(absolute))
        assertNull(descriptor(absolute, MeldEnvironment.QA))
    }

    @Test
    fun `an endpoint off the API host or off the order's own path is refused`() {
        for (bad in listOf(
            "https://evil.example$endpoint",
            "//evil.example$endpoint",
            "http://api-sb.meld.io$endpoint",
            "https://api-sb.meld.io:443$endpoint",
            "https://user@api-sb.meld.io$endpoint",
            "$endpoint?debug=1",
            "$endpoint#fragment",
            "crypto/order/headless/onramp/COINME/ord_1/actions",
            "/crypto/order/headless/onramp/COINME/ord_2/actions",
            "/crypto/order/headless/onramp/BANXA/ord_1/actions",
            "/crypto/order/headless/onramp/coinme/ord_1/actions",
            "/crypto/order/headless/transfer/COINME/ord_1/actions",
            "/crypto/order/headless/onramp/COINME/ord_1/actions/extra",
            "/crypto/order/headless/onramp/COINME/x/../ord_1/actions",
            "/crypto/order/headless/onramp/COINME/ord%5F1/actions",
            "\\crypto\\order\\headless\\onramp\\COINME\\ord_1\\actions",
            42,
        )) {
            assertNull(bad.toString(), descriptor(order(actions(endpoint = bad))))
        }
        assertNull(descriptor(order(actions(version = 2))))
        assertNull(descriptor(order(actions(version = null))))
        assertNull(descriptor(order(id = null)))
        assertNotNull(descriptor(order(provider = null)))
        assertNotNull(descriptor(order(actions(endpoint = "/crypto/order/headless/offramp/COINME/ord_1/actions"))))
    }

    @Test
    fun `the bearer is resolved by JSON pointer`() {
        val root = mapOf("a/b" to mapOf("c~d" to listOf("x", "y")))
        assertEquals("y", PaymentActionDescriptor.resolve("/a~1b/c~0d/1", root))
        for (missing in listOf("/a~1b/c~0d/01", "/a~1b/c~0d/2", "/a~2b", "/a~", "a~1b", "/absent")) {
            assertNull(missing, PaymentActionDescriptor.resolve(missing, root))
        }

        for (token in listOf("", "has space", "line\nbreak", 42, null, "é")) {
            assertNull(token.toString(), descriptor(order(token = token)))
        }
        assertNull(descriptor(order(actions(pointer = "/paymentMethodResponseDetails"))))
        assertNull(descriptor(order(actions(pointer = "paymentMethodResponseDetails/continuationToken"))))
    }

    @Test
    fun `operation declarations must be well formed`() {
        for (operations in listOf(
            emptyList<Any>(),
            listOf(op("CREATE_PAYMENT_SESSION", true), op("CREATE_PAYMENT_SESSION", false)),
            listOf(op("CREATE_PAYMENT_SESSION", "true")),
            listOf(op("CREATE_PAYMENT_SESSION", 1)),
            listOf(op("create_payment_session", true)),
            listOf(op(null, true)),
            listOf("CREATE_PAYMENT_SESSION"),
            "CREATE_PAYMENT_SESSION",
        )) {
            assertNull(operations.toString(), descriptor(order(actions(operations = operations))))
        }
    }

    @Test
    fun `the response comes back undecoded, and nothing when there is none`() {
        fun relay(transport: PaymentActionTransport) =
            client(transport).perform("CREATE_PAYMENT_SESSION", emptyMap(), UUID.randomUUID())

        val rejected = relay { PaymentActionResponse(422, """{"version":1,"code":"PROVIDER_REJECTED"}""") }!!
        assertEquals(422, rejected.status)
        assertEquals("""{"version":1,"code":"PROVIDER_REJECTED"}""", rejected.body)
        assertNull(relay { throw PaymentActionError.Transport(sent = true) })
        assertNull(relay { throw IllegalStateException("boom") })

        var posted = 0
        assertNull(client { posted += 1; PaymentActionResponse(200, """{"version":1}""") }.perform("UNDECLARED", emptyMap(), null))
        assertEquals(0, posted)
    }

    @Test
    fun `the transport gets exactly the built request`() {
        var sent: PaymentActionRequest? = null
        val key = UUID.randomUUID()
        client { sent = it; PaymentActionResponse(200, """{"version":1}""") }
            .perform("CREATE_PAYMENT_SESSION", mapOf("paymentToken" to "pm_1"), key)

        assertEquals("https://api-sb.meld.io$endpoint", sent!!.url.toString())
        assertEquals(key.toString(), sent!!.headers["X-Idempotency-Key"])
        assertEquals("pm_1", JSONObject(String(sent!!.body)).getString("paymentToken"))
    }
}
