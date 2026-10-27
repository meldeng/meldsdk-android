package io.meld.sdk.actions

import io.meld.sdk.actions.ActionFixtures.descriptor
import io.meld.sdk.hosted.VirtualClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URL
import java.util.UUID

class PaymentActionClientTest {
    private val opened = mutableListOf<FakeConnection>()

    private fun client(make: (URL) -> FakeConnection = { FakeConnection(it) }) =
        PaymentActionClient(descriptor(), ActionQueue(DirectExecutor(), VirtualClock())) { url -> make(url).also { opened += it } }

    private fun answer(status: Int, json: Any?) = client { FakeConnection(it, status, json?.toString()?.toByteArray()) }

    private fun failure(client: PaymentActionClient, operation: String = "READ_SUBMISSION", key: UUID? = null): PaymentActionException =
        assertThrows(PaymentActionException::class.java) { client.execute(operation, emptyMap(), key) }

    @Test fun sends_exactly_the_declared_request_without_redirects_caches_or_extra_headers() {
        val key = UUID.randomUUID()
        val client = client { FakeConnection(it, response = """{"version":1,"status":"SYNTHETIC_STATUS"}""".toByteArray()) }

        val answer = client.execute("SUBMIT_WALLET_PAYMENT", mapOf("walletPayment" to mapOf("token" to "t")), key)

        assertEquals(mapOf("version" to 1, "status" to "SYNTHETIC_STATUS"), answer)
        val connection = opened.single()
        assertEquals(ActionFixtures.URL, connection.url.toString())
        assertEquals("POST", connection.requestMethod)
        assertTrue(connection.doOutput)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
        assertEquals(
            mapOf("Content-Type" to "application/json", "Accept" to "application/json", "Cache-Control" to "no-store",
                "Authorization" to "Bearer synthetic-session", "X-Idempotency-Key" to key.toString().lowercase()),
            connection.headers,
        )
        val body = connection.sent.toByteArray()
        assertEquals(body.size, connection.fixedLength)
        val sent = JSONObject(String(body))
        assertEquals(setOf("version", "operation", "walletPayment"), sent.keySet())
        assertEquals("SUBMIT_WALLET_PAYMENT", sent.getString("operation"))
        assertEquals(1, connection.disconnected)
    }

    @Test fun a_read_carries_no_idempotency_key() {
        val client = client()
        client.execute("READ_SUBMISSION", emptyMap(), null)
        assertFalse(opened.single().headers.containsKey("X-Idempotency-Key"))
        assertEquals("""{"version":1,"operation":"READ_SUBMISSION"}""", String(opened.single().sent.toByteArray()))
    }

    @Test fun an_undeclared_request_never_opens_a_connection() {
        assertSame(PaymentActionException.InvalidRequest, failure(client(), "UNKNOWN"))
        assertSame(PaymentActionException.InvalidRequest, failure(client(), "SUBMIT_WALLET_PAYMENT"))
        assertSame(PaymentActionException.InvalidRequest, failure(client(), "READ_SUBMISSION", UUID.randomUUID()))
        assertTrue(opened.isEmpty())
    }

    @Test fun a_redirect_is_refused_and_reported_as_its_status() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val error = failure(answer(status, null))
            assertEquals("http($status)", error.toString())
        }
        assertEquals(5, opened.size)
        assertTrue(opened.all { !it.instanceFollowRedirects && it.disconnected == 1 })
    }

    @Test fun error_bodies_never_surface() {
        val error = failure(answer(400, JSONObject(mapOf("secret" to "synthetic-sensitive-response"))))
        assertEquals("http(400)", error.toString())
        assertEquals(null, error.message)
        assertFalse(error.toString().contains("synthetic"))
        assertEquals("http(500)", failure(answer(500, "not json")).toString())
        assertEquals("http(404)", failure(answer(404, null)).toString())
    }

    @Test fun a_normalized_code_needs_version_1_a_known_code_and_its_own_status() {
        val conflict = JSONObject(mapOf("version" to 1, "code" to "REQUEST_CONFLICT", "detail" to "synthetic-private"))
        assertEquals("action:REQUEST_CONFLICT", failure(answer(409, conflict)).toString())
        assertEquals("http(401)", failure(answer(401, conflict)).toString())
        assertEquals("http(409)", failure(answer(409, JSONObject(mapOf("version" to 2, "code" to "REQUEST_CONFLICT")))).toString())
        assertEquals("action:OPERATION_IN_FLIGHT",
            failure(answer(425, JSONObject(mapOf("version" to 1, "code" to "OPERATION_IN_FLIGHT")))).toString())
    }

    @Test fun a_success_must_be_a_version_1_object() {
        for (body in listOf("""{"version":2}""", """{"version":true}""", """{"status":"SUBMITTED"}""", """{"version":"1"}""",
            "[1]", "not json", "")) {
            assertSame(body, PaymentActionException.InvalidResponse, failure(answer(200, body)))
        }
        assertEquals(mapOf("version" to 1), client { FakeConnection(it, 201) }.execute("READ_SUBMISSION", emptyMap(), null))
        assertSame(PaymentActionException.InvalidResponse, failure(answer(-1, null)))
    }

    @Test fun responses_are_bounded_at_64_kib_and_reading_stops_one_byte_past_it() {
        val envelope = """{"version":1,"pad":""}""".length
        val fits = """{"version":1,"pad":"${"x".repeat(65_536 - envelope)}"}"""
        assertEquals(1, answer(200, fits).execute("READ_SUBMISSION", emptyMap(), null)["version"])

        val huge = """{"version":1,"pad":"${"x".repeat(1_000_000)}"}"""
        assertSame(PaymentActionException.InvalidResponse, failure(answer(200, huge)))
        assertEquals(65_537, opened.last().bytesRead)
        assertEquals("http(400)", failure(answer(400, """{"version":1,"code":"INVALID_REQUEST","pad":"${"x".repeat(70_000)}"}""")).toString())
        assertEquals(65_537, opened.last().bytesRead)
    }

    @Test fun every_io_failure_is_a_transport_failure_and_always_disconnects() {
        for (stage in listOf("connect", "write", "status", "read")) {
            assertSame(stage, PaymentActionException.Transport, failure(client { FakeConnection(it, failAt = stage) }))
        }
        assertTrue(opened.all { it.disconnected == 1 })
        assertSame(PaymentActionException.Transport, failure(PaymentActionClient(descriptor(), ActionQueue(DirectExecutor(), VirtualClock())) { throw IOException("synthetic") }))
        assertSame(PaymentActionException.Transport, failure(PaymentActionClient(descriptor(), ActionQueue(DirectExecutor(), VirtualClock())) { throw SecurityException("synthetic") }))
    }
}
