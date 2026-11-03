package io.meld.sdk.actions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentActionErrorTest {
    private fun body(vararg fields: Pair<String, Any?>) = JSONObject(mapOf(*fields)).toString().toByteArray()

    @Test fun normalized_errors_require_a_known_version_code_and_matching_http_status() {
        val valid = body("version" to 1, "code" to "AUTHORIZATION_REQUIRED", "secret" to "synthetic-private")
        assertEquals("action:AUTHORIZATION_REQUIRED", PaymentActionFailureCode.decode(valid, 401).toString())
        for (json in listOf(body("version" to true, "code" to "AUTHORIZATION_REQUIRED"), body("version" to 2, "code" to "AUTHORIZATION_REQUIRED"),
            body("code" to "AUTHORIZATION_REQUIRED"), body("version" to 1, "code" to "unknown-sensitive-value"),
            body("version" to 1, "code" to "authorization_required"), body("version" to 1), "[1]".toByteArray(), "not json".toByteArray(), null)) {
            assertEquals("http(401)", PaymentActionFailureCode.decode(json, 401).toString())
        }
        assertEquals("http(500)", PaymentActionFailureCode.decode(valid, 500).toString())
        val padded = body("version" to 1, "code" to "AUTHORIZATION_REQUIRED", "pad" to "x".repeat(65_536))
        assertEquals("http(401)", PaymentActionFailureCode.decode(padded, 401).toString())
    }

    @Test fun every_code_maps_to_the_servers_http_status() {
        val expected = mapOf("INVALID_REQUEST" to 400, "AUTHORIZATION_REQUIRED" to 401, "STATE_NOT_FOUND" to 404,
            "REQUEST_CONFLICT" to 409, "ORDER_STATE_CHANGED" to 409, "CONCURRENT_STATE_CHANGE" to 409,
            "OPERATION_IN_FLIGHT" to 425, "PROVIDER_REJECTED" to 422, "PROVIDER_RATE_LIMITED" to 429,
            "PROVIDER_UNAVAILABLE" to 502, "INVALID_PROVIDER_RESPONSE" to 502, "OUTCOME_UNKNOWN" to 503)
        assertEquals(expected, PaymentActionFailureCode.entries.associate { it.name to it.httpStatus })
        for ((code, status) in expected) {
            val error = PaymentActionFailureCode.decode(body("version" to 1, "code" to code), status) as PaymentActionException.Action
            assertEquals(code, error.code.name)
        }
    }

    @Test fun errors_carry_no_message_cause_or_stack() {
        for (error in listOf(PaymentActionException.Transport, PaymentActionException.Http(400),
            PaymentActionException.Action(PaymentActionFailureCode.OUTCOME_UNKNOWN))) {
            assertNull(error.message)
            assertNull(error.cause)
            assertEquals(0, error.stackTrace.size)
        }
        assertEquals(listOf("invalidDescriptor", "invalidRequest", "invalidResponse", "transport", "storage", "alreadyAttempted"),
            listOf(PaymentActionException.InvalidDescriptor, PaymentActionException.InvalidRequest, PaymentActionException.InvalidResponse,
                PaymentActionException.Transport, PaymentActionException.Storage, PaymentActionException.AlreadyAttempted).map { it.toString() })
    }
}
