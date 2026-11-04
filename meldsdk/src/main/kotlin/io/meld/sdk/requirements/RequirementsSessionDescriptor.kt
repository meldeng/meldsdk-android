package io.meld.sdk.requirements

import io.meld.sdk.MeldEnvironment
import io.meld.sdk.actions.ActionConnector
import io.meld.sdk.actions.ActionEndpoint
import io.meld.sdk.actions.ActionQueue
import io.meld.sdk.actions.ActionRequest
import io.meld.sdk.actions.PaymentActionClient
import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.actions.apiBaseUrl
import io.meld.sdk.actions.declaredRequest
import io.meld.sdk.actions.jsonPointer
import io.meld.sdk.actions.strictBoolean
import io.meld.sdk.actions.strictInt
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

/**
 * The customer-scoped endpoint and bearer a requirements session declares in `requirementsActions`, pinned
 * to the configured environment's API host and to the session's own id. The mint declares the endpoint
 * relative, so a session minted in another environment resolves here too and the server refuses its bearer
 * with 401. Not a data class: a generated `toString` would print the bearer.
 */
internal class RequirementsSessionDescriptor private constructor(
    val endpoint: URI,
    val sessionId: String,
    private val bearer: String,
) : ActionEndpoint {
    override val maxResponseBytes: Int get() = MAX_SNAPSHOT_BYTES

    override fun toString(): String = "RequirementsSessionDescriptor[REDACTED]"

    override fun request(operation: String, fields: Map<String, Any?>, key: UUID?): ActionRequest =
        declaredRequest(endpoint, bearer, OPERATIONS, operation, fields, key)

    companion object {
        const val READ = "READ"
        const val RUN_STEP = "RUN_STEP"

        /** A READ answers with every provider's whole checklist, so it may be four times an order action. */
        const val MAX_SNAPSHOT_BYTES = 262_144

        private const val MAX_BEARER_LENGTH = 16_384
        private val OPERATIONS = mapOf(READ to false, RUN_STEP to true)
        private val SESSION_ID = Regex("[1-9A-HJ-NP-Za-km-z]{1,64}")

        /** The session's declared actions, or [PaymentActionException.InvalidDescriptor]. */
        fun parse(raw: Map<String, Any?>, environment: MeldEnvironment): RequirementsSessionDescriptor {
            val id = (raw["id"] as? String)?.takeIf { SESSION_ID.matches(it) } ?: invalid()
            val actions = raw["requirementsActions"] as? Map<*, *> ?: invalid()
            if (strictInt(actions["version"]) != 1) invalid()
            val declared = try {
                URI(actions["endpoint"] as? String ?: invalid())
            } catch (_: URISyntaxException) {
                invalid()
            }
            if (declared.rawPath.orEmpty().split("/").any { it == "." || it == ".." }) invalid()
            val base = URI(environment.apiBaseUrl)
            val url = URI("${environment.apiBaseUrl}/").resolve(declared)
            if (url.host != base.host || url.scheme != "https" || (url.port != -1 && url.port != 443) ||
                url.rawUserInfo != null || url.rawQuery != null || url.rawFragment != null || url.rawPath != url.path
            ) {
                invalid()
            }
            if (url.path.split("/") != listOf("", "crypto", "onramp", "requirements-sessions", id, "actions")) invalid()
            val pointer = actions["bearerTokenPointer"] as? String ?: invalid()
            val bearer = jsonPointer(raw, pointer) as? String ?: invalid()
            if (bearer.isEmpty() || bearer.length > MAX_BEARER_LENGTH || bearer.any { it.code !in 0x21..0x7E }) invalid()
            if (operations(actions["operations"]) != OPERATIONS) invalid()
            return RequirementsSessionDescriptor(url, id, bearer)
        }

        private fun operations(raw: Any?): Map<String, Boolean> {
            val operations = LinkedHashMap<String, Boolean>()
            for (declaration in raw as? List<*> ?: invalid()) {
                val entry = declaration as? Map<*, *> ?: invalid()
                val operation = entry["operation"] as? String ?: invalid()
                val required = strictBoolean(entry["idempotencyKeyRequired"]) ?: invalid()
                if (operations.put(operation, required) != null) invalid()
            }
            return operations
        }

        private fun invalid(): Nothing = throw PaymentActionException.InvalidDescriptor
    }
}

/**
 * One session's actions on its own ordered thread. [close] delivers nothing more and disconnects the
 * request in flight, so a cancelled loop does not wait for its answer.
 */
internal class SessionTransport(
    descriptor: RequirementsSessionDescriptor,
    queue: ActionQueue = ActionQueue(),
    connector: ActionConnector = ActionConnector { url -> url.openConnection() as HttpURLConnection },
) {
    @Volatile
    private var closed = false

    @Volatile
    private var inFlight: HttpURLConnection? = null

    private val client = PaymentActionClient(descriptor, queue) { url ->
        if (closed) throw IOException("closed")
        connector.open(url).also { connection ->
            inFlight = connection
            if (closed) connection.disconnect()
        }
    }

    /** Sends [operation] after all earlier work. [callback] runs on main, and never after [close]. */
    fun send(operation: String, fields: Map<String, Any?>, key: UUID?, callback: (Result<Map<String, Any?>>) -> Unit) =
        client.send(operation, fields, key) { result ->
            inFlight = null
            callback(result)
        }

    fun close() {
        closed = true
        client.finish()
        inFlight?.disconnect()
    }
}
