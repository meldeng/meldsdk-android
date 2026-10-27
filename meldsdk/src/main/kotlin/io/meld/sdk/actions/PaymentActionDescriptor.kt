package io.meld.sdk.actions

import android.util.Log
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldOrder
import java.net.URI
import java.util.Collections
import java.util.UUID

/**
 * The order-scoped endpoint, bearer and operations an order declares in `paymentActions`. The endpoint
 * is pinned to the configured environment's host, so an order from another backend never parses. Not a
 * data class: a generated `toString` would print the bearer.
 */
internal class PaymentActionDescriptor private constructor(
    val endpoint: URI,
    /** The provider path segment, already checked against `payload.serviceProvider`. */
    val provider: String,
    private val bearer: String,
    val operations: Map<String, Boolean>,
) {
    /** The durable attempt identity; an explicit `:443` names the same endpoint. */
    val identity: String get() = "https://${endpoint.host}${endpoint.path}"

    override fun toString(): String = "PaymentActionDescriptor[REDACTED]"

    /** The POST for a declared [operation]. [key] is present exactly when the operation requires one. */
    fun request(operation: String, fields: Map<String, Any?> = emptyMap(), key: UUID? = null): ActionRequest {
        val required = operations[operation] ?: throw PaymentActionException.InvalidRequest
        if (required != (key != null) || "version" in fields || "operation" in fields) {
            throw PaymentActionException.InvalidRequest
        }
        val body = encodeActionObject(fields + mapOf("version" to 1, "operation" to operation))
        if (body.size > MAX_ACTION_BODY_BYTES) throw PaymentActionException.InvalidRequest
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Cache-Control" to "no-store",
            "Authorization" to "Bearer $bearer",
        )
        if (key != null) headers["X-Idempotency-Key"] = key.toString()
        return ActionRequest(endpoint, headers, body)
    }

    companion object {
        private const val TAG = "MeldSDK"
        private const val MAX_BEARER_LENGTH = 16_384
        private val PATH_PREFIX = listOf("", "crypto", "order", "headless", "onramp")
        private val warned = Collections.synchronizedSet(HashSet<Pair<MeldEnvironment, MeldEnvironment>>())

        /** The order's declared actions, or [PaymentActionException.InvalidDescriptor]. */
        fun parse(order: MeldOrder, environment: MeldEnvironment): PaymentActionDescriptor {
            val raw = order.raw["paymentActions"] as? Map<*, *> ?: invalid()
            if (strictInt(raw["version"]) != 1) invalid()
            val orderId = order.id?.takeIf { it.isNotEmpty() } ?: invalid()
            val base = URI(environment.apiBaseUrl)
            val url = try {
                URI("${environment.apiBaseUrl}/").resolve(raw["endpoint"] as? String ?: invalid())
            } catch (_: IllegalArgumentException) {
                invalid()
            }
            if (url.host != base.host) {
                warnIfOtherEnvironment(environment, url.host)
                invalid()
            }
            if (url.scheme != "https" || (url.port != -1 && url.port != 443) || url.rawUserInfo != null ||
                url.rawQuery != null || url.rawFragment != null || url.rawPath != url.path
            ) {
                invalid()
            }
            val path = url.path.split("/")
            if (path.size != 8 || path.subList(0, 5) != PATH_PREFIX || path[5].isEmpty() || path[6] != orderId ||
                path[7] != "actions" || order.serviceProvider != path[5]
            ) {
                invalid()
            }
            val pointer = raw["bearerTokenPointer"] as? String ?: invalid()
            val bearer = jsonPointer(order.raw, pointer) as? String ?: invalid()
            if (bearer.isEmpty() || bearer.length > MAX_BEARER_LENGTH || bearer.any { it.code !in 0x21..0x7E }) invalid()
            return PaymentActionDescriptor(url, path[5], bearer, operations(raw["operations"]))
        }

        private fun operations(raw: Any?): Map<String, Boolean> {
            val declarations = (raw as? List<*>)?.takeIf { it.isNotEmpty() } ?: invalid()
            val operations = LinkedHashMap<String, Boolean>()
            for (declaration in declarations) {
                val entry = declaration as? Map<*, *> ?: invalid()
                val operation = (entry["operation"] as? String)?.takeIf { it.isNotEmpty() } ?: invalid()
                val required = strictBoolean(entry["idempotencyKeyRequired"]) ?: invalid()
                if (operations.put(operation, required) != null) invalid()
            }
            return operations
        }

        /** The commonest misconfiguration, logged once per pair: the order came from another Meld backend. */
        private fun warnIfOtherEnvironment(configured: MeldEnvironment, host: String?) {
            val other = MeldEnvironment.entries.firstOrNull { URI(it.apiBaseUrl).host == host } ?: return
            if (warned.add(configured to other)) {
                Log.w(
                    TAG,
                    "configured '${configured.raw}', order endpoint for $host ('${other.raw}'). Payment actions are " +
                        "unavailable; configure the backend that created the order.",
                )
            }
        }

        private fun invalid(): Nothing = throw PaymentActionException.InvalidDescriptor
    }
}

/** One action POST. Redacted: its headers hold the bearer and its body the customer's payment. */
internal class ActionRequest(val url: URI, val headers: Map<String, String>, val body: ByteArray) {
    override fun toString(): String = "ActionRequest[REDACTED]"
}
