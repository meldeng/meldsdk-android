package io.meld.sdk.wallet

import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.wallet.WalletActionResponse.State
import io.meld.sdk.wallet.WalletFixtures.payment
import io.meld.sdk.wallet.WalletFixtures.response
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletActionResponseTest {
    private fun invalidResponse(message: String, decode: () -> Any) =
        assertSame(message, PaymentActionException.InvalidResponse, assertThrows(message, PaymentActionException::class.java) { decode() })

    private fun invalidRequest(message: String, fields: () -> Any) =
        assertSame(message, PaymentActionException.InvalidRequest, assertThrows(message, PaymentActionException::class.java) { fields() })

    @Test fun known_pairs_decode_and_unknown_or_repaired_values_decode_as_an_unresolved_attempt() {
        for (state in listOf("NOT_STARTED", "SUBMITTED", "IN_PROGRESS", "UNKNOWN", "FAILED", "EXPIRED", "VERIFICATION_REQUIRED")) {
            val json = response(state)
            val decoded = WalletActionResponse.decode(json)
            assertEquals(state, decoded.state.name)
            assertTrue(decoded.recognized)
            assertEquals(state == "VERIFICATION_REQUIRED", decoded.verification != null)

            val repaired = WalletActionResponse.decode(json + ("nextStep" to "CREATE_ANOTHER_PAYMENT"))
            assertEquals(state, State.UNKNOWN, repaired.state)
            assertFalse(state, repaired.recognized)
            assertNull(state, repaired.verification)

            for (version in listOf(true, "1", 2, null)) invalidResponse("$state $version") { WalletActionResponse.decode(json + ("version" to version)) }
            invalidResponse("$state no next") { WalletActionResponse.decode(json - "nextStep") }
            invalidResponse("$state no status") { WalletActionResponse.decode(json - "status") }
            invalidResponse("$state numeric status") { WalletActionResponse.decode(json + ("status" to 1)) }
        }
        val added = WalletActionResponse.decode(response("NEW_PROVIDER_STATE"))
        assertEquals(State.UNKNOWN, added.state)
        assertFalse(added.recognized)
        assertTrue(WalletActionResponse.decode(response("SUBMITTED") + ("version" to 1.0)).recognized)
    }

    @Test fun only_a_verification_required_answer_carries_a_verification() {
        invalidResponse("stray") { WalletActionResponse.decode(response("SUBMITTED") + ("verification" to mapOf("url" to "https://example.com"))) }
        assertNull(WalletActionResponse.decode(response("SUBMITTED") + ("verification" to null)).verification)
        invalidResponse("missing") { WalletActionResponse.decode(response("VERIFICATION_REQUIRED") - "verification") }
        invalidResponse("null") { WalletActionResponse.decode(response("VERIFICATION_REQUIRED") + ("verification" to null)) }
        invalidResponse("string") { WalletActionResponse.decode(response("VERIFICATION_REQUIRED") + ("verification" to "https://x")) }
    }

    @Test fun verification_requires_https_a_disposition_and_an_expiry_without_credentials() {
        @Suppress("UNCHECKED_CAST")
        val original = response("VERIFICATION_REQUIRED")["verification"] as Map<String, Any?>
        for (field in listOf("url", "paymentDisposition", "expiresAt")) {
            invalidResponse(field) { WalletVerification.decode(original - field) }
        }
        for (url in listOf("http://sandbox-exchange.mrcr.io/x", "https://user:pass@sandbox-exchange.mrcr.io/x",
            "https://sandbox-exchange.mrcr.io:8080/x", "javascript:alert(1)", "/relative", "https:///x", "https://bad host/x", 42)) {
            invalidResponse("$url") { WalletVerification.decode(original + ("url" to url)) }
        }
        invalidResponse("disposition") { WalletVerification.decode(original + ("paymentDisposition" to "START_ANOTHER_NATIVE_PAYMENT")) }
        invalidResponse("expiry") { WalletVerification.decode(original + ("expiresAt" to "tomorrow")) }

        val verified = WalletVerification.decode(original)
        assertEquals("https://sandbox-exchange.mrcr.io/verification?session=synthetic", verified.url.toString())
        assertEquals(WalletVerification.Disposition.RESUME_PAYMENT, verified.disposition)
        assertEquals(WalletFixtures.NOW + 300_000, verified.expiresAtMillis)
        assertEquals("WalletVerification[REDACTED]", verified.toString())
        assertEquals(WalletVerification.Disposition.REPLACE_PAYMENT_IN_HOSTED_FLOW,
            WalletVerification.decode(original + ("paymentDisposition" to "REPLACE_PAYMENT_IN_HOSTED_FLOW")).disposition)
        assertEquals(443, WalletVerification.decode(original + ("url" to "https://sandbox-exchange.mrcr.io:443/x")).url.port)
    }

    @Test fun rfc_3339_accepts_zero_to_nine_fraction_digits_and_offsets() {
        assertEquals(1_900_000_000_000L, parseRfc3339("2030-03-17T17:46:40Z"))
        assertEquals(1_900_000_000_100L, parseRfc3339("2030-03-17T17:46:40.1Z"))
        assertEquals(1_900_000_000_123L, parseRfc3339("2030-03-17T17:46:40.123Z"))
        assertEquals(1_900_000_000_123L, parseRfc3339("2030-03-17T17:46:40.123456789Z"))
        assertEquals(1_900_000_000_000L, parseRfc3339("2030-03-17T19:46:40+02:00"))
        assertEquals(1_900_000_000_000L, parseRfc3339("2030-03-17T12:16:40.000-05:30"))
        assertEquals(1_709_270_999_999L, parseRfc3339("2024-02-29T23:59:59.999-05:30"))
        assertEquals(951_818_400_123L, parseRfc3339("2000-03-01T00:00:00.123+14:00"))
        assertEquals(-1_000L, parseRfc3339("1969-12-31T23:59:59Z"))
        for (bad in listOf("2030-03-17T17:46:40", "2030-03-17 17:46:40Z", "2030-03-17t17:46:40z", "2030-03-17T17:46:40.Z",
            "2030-03-17T17:46:40.1234567890Z", "2030-03-17T17:46:40+0200", "2030-03-17T17:46:40+02", "2030-13-01T00:00:00Z",
            "2030-00-01T00:00:00Z", "2030-02-29T00:00:00Z", "2030-04-31T00:00:00Z", "2030-01-00T00:00:00Z", "2030-01-01T24:00:00Z",
            "2030-01-01T00:60:00Z", "2030-01-01T00:00:60Z", "2030-01-01T00:00:00+24:00", "2030-01-01T00:00:00+00:60",
            "30-01-01T00:00:00Z", " 2030-01-01T00:00:00Z", "2030-01-01T00:00:00Z ", "")) {
            assertNull(bad, parseRfc3339(bad))
        }
        assertEquals(parseRfc3339("2100-03-01T00:00:00Z")!! - 86_400_000, parseRfc3339("2100-02-28T00:00:00Z"))
    }

    @Test fun wallet_fields_have_the_servers_shape() {
        val fields = payment.actionFields()
        @Suppress("UNCHECKED_CAST")
        val wallet = fields.getValue("walletPayment") as Map<String, Any?>
        assertEquals(setOf("walletPayment"), fields.keys)
        assertEquals(setOf("token", "email", "firstName", "lastName", "billingAddress"), wallet.keys)
        assertEquals(mapOf("countryCode" to "LT", "streetLine1" to "1 Test Street", "city" to "Test", "zipCode" to "12345"),
            wallet["billingAddress"])
        assertEquals("WalletPayment[REDACTED]", payment.toString())
        assertEquals("WalletBillingAddress[REDACTED]", payment.billing.toString())
    }

    @Test fun a_google_pay_json_token_passes_without_a_base64_rule() {
        assertEquals(payment.token, (payment.actionFields()["walletPayment"] as Map<*, *>)["token"])
        assertTrue(JSONObject(payment.token).has("signedMessage"))
        for (token in listOf("not-base64", "dG9rZW4=", "x".repeat(49_152))) {
            WalletPayment(token, payment.firstName, payment.lastName, payment.email, payment.billing).actionFields()
        }
    }

    @Test fun wallet_data_limits_are_checked_before_network_dispatch() {
        val valid = payment
        for ((token, first, email) in listOf(
            Triple("", valid.firstName, valid.email),
            Triple("x".repeat(49_153), valid.firstName, valid.email),
            Triple("é".repeat(24_577), valid.firstName, valid.email),
            Triple(valid.token, "x".repeat(256), valid.email),
            Triple(valid.token, "line\nbreak", valid.email),
            Triple(valid.token, "zero​width", valid.email),
            Triple(valid.token, " ", valid.email),
            Triple(valid.token, valid.firstName, null),
            Triple(valid.token, valid.firstName, "invalid-email"),
            Triple(valid.token, valid.firstName, "a@b"),
            Triple(valid.token, valid.firstName, "x".repeat(250) + "@b.co"),
        )) {
            invalidRequest("$first $email") { WalletPayment(token, first, valid.lastName, email, valid.billing).actionFields() }
        }
        invalidRequest("last") { WalletPayment(valid.token, valid.firstName, "", valid.email, valid.billing).actionFields() }
        invalidRequest("no billing") { WalletPayment(valid.token, valid.firstName, valid.lastName, valid.email, WalletBillingAddress("", null)).actionFields() }
        for (country in listOf("lt", "LTT", "L\n", "L")) {
            val billing = WalletBillingAddress(countryCode = country, streetLine1 = "1 Test")
            invalidRequest(country) { WalletPayment(valid.token, valid.firstName, valid.lastName, valid.email, billing).actionFields() }
        }
        for (billing in listOf(WalletBillingAddress("LT", zipCode = "1".repeat(33)), WalletBillingAddress("LT", stateCode = "s".repeat(129)),
            WalletBillingAddress("LT", streetLine2 = "\u0007"), WalletBillingAddress("LT", city = "   "))) {
            invalidRequest("billing") { WalletPayment(valid.token, valid.firstName, valid.lastName, valid.email, billing).actionFields() }
        }
        invalidRequest("escaped body over 64 KiB") {
            WalletPayment("\"".repeat(40_000), valid.firstName, valid.lastName, valid.email, valid.billing).actionFields()
        }
    }
}
