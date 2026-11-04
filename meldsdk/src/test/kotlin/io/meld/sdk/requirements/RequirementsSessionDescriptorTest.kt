package io.meld.sdk.requirements

import io.meld.sdk.MeldEnvironment
import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.actions.apiBaseUrl
import io.meld.sdk.toMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RequirementsSessionDescriptorTest {
    private val fixture = SessionFixture("banxa-uphold")
    private val id = "Ny9YeB5gTh8WaQc3DrUf7k"
    private val path = "/crypto/onramp/requirements-sessions/$id/actions"

    @Suppress("UNCHECKED_CAST")
    private fun session(actions: Map<String, Any?> = emptyMap(), session: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val declared = fixture.mint["requirementsActions"] as Map<String, Any?>
        return fixture.mint + ("requirementsActions" to declared + actions) + session
    }

    private fun parse(raw: Map<String, Any?>, environment: MeldEnvironment = MeldEnvironment.SANDBOX) =
        RequirementsSessionDescriptor.parse(raw, environment)

    private fun rejects(message: String, raw: Map<String, Any?>, environment: MeldEnvironment = MeldEnvironment.SANDBOX) {
        val error = assertThrows(message, PaymentActionException::class.java) { parse(raw, environment) }
        assertSame(message, PaymentActionException.InvalidDescriptor, error)
    }

    @Test fun every_fixture_session_parses_and_builds_only_its_two_operations() {
        val names = RequirementsFixtures.sessionNames()
        assertTrue(names.size >= 9)
        for (name in names) {
            val fx = SessionFixture(name)
            val descriptor = parse(fx.mint)
            assertEquals(name, fx.endpoint, descriptor.endpoint.toString())
            assertEquals(fx.mint["id"], descriptor.sessionId)
            assertEquals(fx.mint["id"], fx.session.id)
        }

        val descriptor = parse(fixture.mint)
        val read = descriptor.request(RequirementsSessionDescriptor.READ)
        assertEquals("https://api-sb.meld.io$path", read.url.toString())
        assertEquals(mapOf("Content-Type" to "application/json", "Accept" to "application/json", "Cache-Control" to "no-store",
            "Authorization" to "Bearer eyJhbGciOiJIUzI1NiJ9.fixture.requirements-session-token"), read.headers)
        assertEquals(mapOf("version" to 1, "operation" to "READ"), JSONObject(String(read.body)).toMap())

        val key = UUID.randomUUID()
        val run = descriptor.request(RequirementsSessionDescriptor.RUN_STEP, mapOf("stepId" to "*:MELD_KYC:MELD_KYC_APPROVED"), key)
        assertEquals(key.toString(), run.headers["X-Idempotency-Key"])
        assertEquals(mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to "*:MELD_KYC:MELD_KYC_APPROVED"),
            JSONObject(String(run.body)).toMap())

        for (request in listOf({ descriptor.request("READ", key = key) }, { descriptor.request("RUN_STEP") },
            { descriptor.request("READ_SUBMISSION") }, { descriptor.request("READ", mapOf("operation" to "RUN_STEP")) })) {
            assertSame(PaymentActionException.InvalidRequest, assertThrows(PaymentActionException::class.java) { request() })
        }
        assertEquals(262_144, descriptor.maxResponseBytes)
    }

    @Test fun the_descriptor_and_the_session_never_print_the_bearer() {
        assertEquals("RequirementsSessionDescriptor[REDACTED]", parse(fixture.mint).toString())
        assertEquals("[MeldRequirementsSession REDACTED]", fixture.session.toString())
        assertFalse(fixture.session.toString().contains("fixture"))
    }

    @Test fun rejects_any_endpoint_but_this_session_s_actions_on_the_configured_backend() {
        for (endpoint in listOf(
            "https://other.example$path",
            "http://api-sb.meld.io$path",
            "https://api.meld.io$path",
            "https://api-qa.meld.io$path",
            "//other.example$path",
            "https://user:pass@api-sb.meld.io$path",
            "https://api-sb.meld.io:8443$path",
            "https://api-sb.meld.io:bad$path",
            "/crypto/onramp/requirements-sessions/Ny9YeB5gTh8WaQc3DrUf7z/actions",
            "/crypto/onramp/requirements-sessions//actions",
            "/crypto/onramp/requirements-session/$id/actions",
            "/crypto/order/headless/onramp/BANXA/$id/actions",
            "/crypto/onramp/requirements-sessions/$id",
            "$path/",
            "$path/extra",
            "/crypto/onramp/requirements-sessions/$id/./actions",
            "/crypto/onramp/requirements-sessions/x/../$id/actions",
            "crypto/onramp/requirements-sessions/x/../$id/actions",
            "crypto/./onramp/requirements-sessions/$id/actions",
            "/crypto/onramp/requirements-sessions/${id.dropLast(1)}%6B/actions",
            "$path?token=synthetic",
            "$path?",
            "$path#fragment",
            "\\crypto\\onramp\\requirements-sessions\\$id\\actions",
            "javascript:alert(1)",
            "",
        )) {
            rejects(endpoint, session(mapOf("endpoint" to endpoint)))
        }
        rejects("number", session(mapOf("endpoint" to 1)))
        rejects("missing", session(mapOf("endpoint" to null)))
    }

    @Test fun relative_absolute_and_explicit_port_endpoints_resolve_against_the_configured_backend() {
        val url = "https://api-sb.meld.io$path"
        for (endpoint in listOf(path, path.removePrefix("/"), url, "https://api-sb.meld.io:443$path")) {
            assertEquals(endpoint, url, parse(session(mapOf("endpoint" to endpoint))).endpoint.toString().replace(":443", ""))
        }
        for (environment in MeldEnvironment.entries) {
            assertEquals("${environment.apiBaseUrl}$path", parse(fixture.mint, environment).endpoint.toString())
            for (other in MeldEnvironment.entries.filter { it != environment }) {
                rejects("$environment accepts $other", session(mapOf("endpoint" to "${other.apiBaseUrl}$path")), environment)
            }
        }
    }

    @Test fun the_session_id_is_base58_and_names_the_endpoint() {
        for (bad in listOf<Any?>(null, "", 7, "0y9YeB5gTh8WaQc3DrUf7k", "Oy9YeB5gTh8WaQc3DrUf7k", "Iy9YeB5gTh8WaQc3DrUf7k",
            "ly9YeB5gTh8WaQc3DrUf7k", "Ny9YeB5g-h8WaQc3DrUf7k", "x".repeat(65))) {
            rejects("$bad", session(session = mapOf("id" to bad)))
            if (bad is String) {
                rejects("$bad in both", session(mapOf("endpoint" to "/crypto/onramp/requirements-sessions/$bad/actions"), mapOf("id" to bad)))
            }
        }
        val longest = "z".repeat(64)
        val descriptor = parse(session(mapOf("endpoint" to "/crypto/onramp/requirements-sessions/$longest/actions"), mapOf("id" to longest)))
        assertEquals(longest, descriptor.sessionId)
    }

    @Test fun the_version_is_a_strict_1_and_the_descriptor_must_be_there() {
        for (version in listOf<Any?>(true, "1", 0, 1.5, 2, null)) rejects("$version", session(mapOf("version" to version)))
        for (version in listOf<Any>(1L, 1.0)) parse(session(mapOf("version" to version)))
        rejects("absent", fixture.mint - "requirementsActions")
        rejects("not an object", fixture.mint + ("requirementsActions" to "actions"))
    }

    @Test fun the_bearer_is_printable_ascii_of_at_most_16384_characters_at_the_declared_pointer() {
        for (pointer in listOf("token", "/missing", "/requirementsActions", "/invalid~2", "", 7, null)) {
            rejects("$pointer", session(mapOf("bearerTokenPointer" to pointer)))
        }
        for (token in listOf<Any?>("", "has space", "line\nbreak", "é", "x".repeat(16_385), "\u007f", 42, null)) {
            rejects("$token".take(20), session(session = mapOf("token" to token)))
        }
        val longest = parse(session(session = mapOf("token" to "~".repeat(16_384))))
        assertEquals("Bearer " + "~".repeat(16_384), longest.request("READ").headers["Authorization"])
        val nested = parse(session(mapOf("bearerTokenPointer" to "/auth/0"), mapOf("auth" to listOf("synthetic-nested"))))
        assertEquals("Bearer synthetic-nested", nested.request("READ").headers["Authorization"])
    }

    @Test fun the_operations_are_exactly_read_and_run_step_with_their_keys() {
        val read = mapOf("operation" to "READ", "idempotencyKeyRequired" to false)
        val run = mapOf("operation" to "RUN_STEP", "idempotencyKeyRequired" to true)
        for (operations in listOf<Any?>(
            null, emptyList<Any>(), listOf(read), listOf(run), listOf(read, run, read),
            listOf(read, run, mapOf("operation" to "DELETE", "idempotencyKeyRequired" to true)),
            listOf(read + ("idempotencyKeyRequired" to true), run), listOf(read, run + ("idempotencyKeyRequired" to false)),
            listOf(read + ("idempotencyKeyRequired" to "false"), run), listOf(read - "idempotencyKeyRequired", run),
            listOf(read + ("operation" to "read"), run), listOf("READ", "RUN_STEP"),
        )) {
            rejects("$operations", session(mapOf("operations" to operations)))
        }
        parse(session(mapOf("operations" to listOf(run, read))))
    }

    @Test fun from_json_decodes_the_mint_response_and_refuses_anything_else() {
        val json = jsonOf(fixture.mint).toString()
        assertEquals(id, MeldRequirementsSession.fromJson(json).id)
        assertSame(MeldRequirementsSessionException.Malformed,
            assertThrows(MeldRequirementsSessionException::class.java) { MeldRequirementsSession.fromJson("[1]") })
        assertSame(MeldRequirementsSessionException.Malformed,
            assertThrows(MeldRequirementsSessionException::class.java) { MeldRequirementsSession.fromJson("not json") })
        val invalid = jsonOf(session(mapOf("endpoint" to "https://other.example$path"))).toString()
        assertSame(MeldRequirementsSessionException.Invalid,
            assertThrows(MeldRequirementsSessionException::class.java) { MeldRequirementsSession.fromJson(invalid) })
        val qa = jsonOf(session(mapOf("endpoint" to "https://api-qa.meld.io$path"))).toString()
        assertTrue(MeldRequirementsSession.fromJson(qa).id == id)
    }
}
