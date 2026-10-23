package io.meld.sdk.actions

import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldOrder
import io.meld.sdk.actions.ActionFixtures.ENDPOINT
import io.meld.sdk.actions.ActionFixtures.descriptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.UUID

class PaymentActionDescriptorTest {
    private fun rejects(message: String, parse: () -> Any) {
        val error = assertThrows(message, PaymentActionException::class.java) { parse() }
        assertSame(message, PaymentActionException.InvalidDescriptor, error)
    }

    private fun rejectsRequest(message: String, request: () -> Any) {
        val error = assertThrows(message, PaymentActionException::class.java) { request() }
        assertSame(message, PaymentActionException.InvalidRequest, error)
    }

    @Test fun builds_only_the_declared_order_scoped_request() {
        val descriptor = descriptor()
        val key = UUID.randomUUID()
        val request = descriptor.request("SUBMIT_WALLET_PAYMENT", mapOf("walletPayment" to mapOf("token" to "t", "email" to null)), key)

        assertEquals(ActionFixtures.URL, request.url.toString())
        assertEquals(
            mapOf("Content-Type" to "application/json", "Accept" to "application/json", "Cache-Control" to "no-store",
                "Authorization" to "Bearer synthetic-session", "X-Idempotency-Key" to key.toString().lowercase()),
            request.headers,
        )
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(request.headers.getValue("X-Idempotency-Key")))
        val body = JSONObject(String(request.body, Charsets.UTF_8))
        assertEquals(setOf("version", "operation", "walletPayment"), body.keySet())
        assertEquals(1, body.getInt("version"))
        assertEquals("SUBMIT_WALLET_PAYMENT", body.getString("operation"))
        assertEquals(setOf("token"), body.getJSONObject("walletPayment").keySet())

        val read = descriptor.request("READ_SUBMISSION")
        assertFalse(read.headers.containsKey("X-Idempotency-Key"))
        assertEquals("""{"version":1,"operation":"READ_SUBMISSION"}""".let(::JSONObject).toMap(), JSONObject(String(read.body)).toMap())

        assertEquals("TEST_PROVIDER", descriptor.provider)
        assertEquals(mapOf("READ_SUBMISSION" to false, "SUBMIT_WALLET_PAYMENT" to true), descriptor.operations)
        assertEquals("PaymentActionDescriptor[REDACTED]", descriptor.toString())
        assertEquals("ActionRequest[REDACTED]", request.toString())
    }

    @Test fun rejects_cross_origin_wrong_order_provider_and_noncanonical_endpoints() {
        for (endpoint in listOf(
            "https://other.example$ENDPOINT",
            "http://api-sb.meld.io$ENDPOINT",
            "https://api.meld.io$ENDPOINT",
            "https://api-qa.meld.io$ENDPOINT",
            "//other.example$ENDPOINT",
            "https://user:pass@api-sb.meld.io$ENDPOINT",
            "https://api-sb.meld.io:8443$ENDPOINT",
            "/crypto/order/headless/onramp/TEST_PROVIDER/other-order/actions",
            "/crypto/order/headless/onramp/OTHER_PROVIDER/test-order/actions",
            "/crypto/order/headless/onramp//test-order/actions",
            "/crypto/order/headless/offramp/TEST_PROVIDER/test-order/actions",
            "/crypto/order/headless/onramp/TEST_PROVIDER/test-order/actions/",
            "/crypto/order/headless/onramp/TEST_PROVIDER/test-order/other",
            "/crypto/order/headless/onramp/TEST_PROVIDER/test-order",
            "/crypto/order/headless/onramp/TEST_PROVIDER/test-order/../test-order/actions",
            "$ENDPOINT?token=synthetic",
            "$ENDPOINT?",
            "$ENDPOINT#fragment",
            "/crypto/order/headless/onramp/TEST_PROVIDER/test%2Dorder/actions",
            "javascript:alert(1)",
            "https://api-sb.meld.io:bad$ENDPOINT",
            "",
        )) {
            rejects(endpoint) { descriptor(mapOf("endpoint" to endpoint)) }
        }
        rejects("number") { descriptor(mapOf("endpoint" to 1)) }
        rejects("missing") { descriptor(mapOf("endpoint" to null)) }
    }

    @Test fun relative_and_absolute_endpoints_resolve_against_the_configured_backend() {
        assertEquals(ActionFixtures.URL, descriptor(mapOf("endpoint" to ENDPOINT.removePrefix("/"))).endpoint.toString())
        assertEquals(ActionFixtures.URL, descriptor(mapOf("endpoint" to ActionFixtures.URL)).endpoint.toString())
        for ((environment, host) in listOf(MeldEnvironment.SANDBOX to "api-sb.meld.io", MeldEnvironment.QA to "api-qa.meld.io",
            MeldEnvironment.PRODUCTION to "api.meld.io")) {
            assertEquals("https://$host$ENDPOINT", descriptor(environment = environment).endpoint.toString())
            for (other in MeldEnvironment.entries.filter { it != environment }) {
                rejects("$environment accepts $other") {
                    descriptor(mapOf("endpoint" to "${other.apiBaseUrl}$ENDPOINT"), environment)
                }
            }
        }
    }

    @Test fun default_https_port_uses_the_same_durable_identity() {
        val implicit = descriptor()
        val explicit = descriptor(mapOf("endpoint" to "https://api-sb.meld.io:443$ENDPOINT"))
        assertEquals("https://api-sb.meld.io$ENDPOINT", implicit.identity)
        assertEquals(implicit.identity, explicit.identity)
    }

    @Test fun versions_are_strict_integers_and_a_bridged_double_is_one() {
        for (version in listOf<Any?>(true, "1", 0, 1.5, 2, null)) {
            rejects("$version") { descriptor(mapOf("version" to version)) }
        }
        for (version in listOf<Any>(1L, 1.0, 1.0f)) assertEquals(ActionFixtures.URL, descriptor(mapOf("version" to version)).endpoint.toString())
        val decoded = MeldOrder.fromJson(JSONObject(ActionFixtures.json()).toString())
        assertEquals(ActionFixtures.URL, PaymentActionDescriptor.parse(decoded, MeldEnvironment.SANDBOX).endpoint.toString())
    }

    @Test fun the_order_must_declare_actions_and_an_id() {
        for (actions in listOf(null, "actions", emptyMap<String, Any>(), mapOf("version" to 1), mapOf("version" to 2))) {
            rejects("$actions") { PaymentActionDescriptor.parse(ActionFixtures.order(ActionFixtures.json() + ("paymentActions" to actions)), MeldEnvironment.SANDBOX) }
        }
        rejects("absent") { PaymentActionDescriptor.parse(ActionFixtures.order(ActionFixtures.json() - "paymentActions"), MeldEnvironment.SANDBOX) }
        for (id in listOf(null, "")) {
            rejects("id $id") { PaymentActionDescriptor.parse(ActionFixtures.order(ActionFixtures.json() + ("id" to id)), MeldEnvironment.SANDBOX) }
        }
        val anyProvider = ActionFixtures.order(ActionFixtures.json() - "payload")
        assertEquals("TEST_PROVIDER", PaymentActionDescriptor.parse(anyProvider, MeldEnvironment.SANDBOX).provider)
    }

    @Test fun the_bearer_pointer_resolves_against_the_whole_order() {
        for (pointer in listOf("paymentMethodResponseDetails/sessionToken", "/missing", "/paymentMethodResponseDetails",
            "/invalid~2pointer", "/invalid~", "/id/0", "", 7, null)) {
            rejects("$pointer") { descriptor(mapOf("bearerTokenPointer" to pointer)) }
        }
        val json = ActionFixtures.json() + ("a/b" to mapOf("~key" to listOf("synthetic-escaped"))) +
            ("paymentActions" to ActionFixtures.actions() + ("bearerTokenPointer" to "/a~1b/~0key/0"))
        val escaped = PaymentActionDescriptor.parse(ActionFixtures.order(json), MeldEnvironment.SANDBOX)
        assertEquals("Bearer synthetic-escaped", escaped.request("READ_SUBMISSION").headers["Authorization"])
        val leadingZero = json + ("paymentActions" to ActionFixtures.actions() + ("bearerTokenPointer" to "/a~1b/~0key/00"))
        rejects("leading zero") { PaymentActionDescriptor.parse(ActionFixtures.order(leadingZero), MeldEnvironment.SANDBOX) }
    }

    @Test fun the_bearer_is_printable_ascii_of_at_most_16384_characters() {
        fun withToken(token: Any?) = ActionFixtures.order(ActionFixtures.json() + ("paymentMethodResponseDetails" to mapOf("sessionToken" to token)))
        for (token in listOf<Any?>("", "has space", "line\nbreak", "tab\t", "é", "x".repeat(16_385), "\u007f", 42, null)) {
            rejects("$token".take(20)) { PaymentActionDescriptor.parse(withToken(token), MeldEnvironment.SANDBOX) }
        }
        val longest = PaymentActionDescriptor.parse(withToken("~".repeat(16_384)), MeldEnvironment.SANDBOX)
        assertEquals("Bearer " + "~".repeat(16_384), longest.request("READ_SUBMISSION").headers["Authorization"])
    }

    @Test fun operations_are_non_empty_unique_and_strictly_flagged() {
        val read = mapOf("operation" to "READ_SUBMISSION", "idempotencyKeyRequired" to false)
        for (operations in listOf<Any?>(
            emptyList<Any>(), null, "READ_SUBMISSION", listOf("READ_SUBMISSION"),
            listOf(read + ("idempotencyKeyRequired" to 0)), listOf(read + ("idempotencyKeyRequired" to "false")),
            listOf(read - "idempotencyKeyRequired"), listOf(read + ("operation" to "")), listOf(read - "operation"),
            listOf(read, read), listOf(read, read + ("idempotencyKeyRequired" to true)),
        )) {
            rejects("$operations") { descriptor(mapOf("operations" to operations)) }
        }
    }

    @Test fun operation_and_idempotency_requirements_cannot_be_overridden() {
        val descriptor = descriptor()
        rejectsRequest("unknown") { descriptor.request("UNKNOWN") }
        rejectsRequest("read with key") { descriptor.request("READ_SUBMISSION", key = UUID.randomUUID()) }
        rejectsRequest("submit without key") { descriptor.request("SUBMIT_WALLET_PAYMENT") }
        for (field in listOf("version", "operation")) {
            rejectsRequest(field) { descriptor.request("READ_SUBMISSION", mapOf(field to "override")) }
            rejectsRequest("$field null") { descriptor.request("READ_SUBMISSION", mapOf(field to null)) }
        }
        rejectsRequest("not json") { descriptor.request("READ_SUBMISSION", mapOf("value" to Date())) }
        rejectsRequest("not finite") { descriptor.request("READ_SUBMISSION", mapOf("value" to Double.NaN)) }
        rejectsRequest("key") { descriptor.request("READ_SUBMISSION", mapOf("value" to mapOf(1 to "x"))) }
    }

    @Test fun request_bodies_are_bounded_at_64_kib() {
        val descriptor = descriptor()
        val envelope = """{"value":"","version":1,"operation":"READ_SUBMISSION"}""".length
        val fits = descriptor.request("READ_SUBMISSION", mapOf("value" to "x".repeat(65_536 - envelope)))
        assertEquals(65_536, fits.body.size)
        rejectsRequest("over") { descriptor.request("READ_SUBMISSION", mapOf("value" to "x".repeat(65_537 - envelope))) }
    }
}
