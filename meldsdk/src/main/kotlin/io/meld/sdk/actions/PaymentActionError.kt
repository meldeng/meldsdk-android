package io.meld.sdk.actions

/** Why a payment action failed. Carries no message, cause or body, so nothing the server sent can reach a log. */
internal sealed class PaymentActionException(private val label: String) : Exception(null, null, false, false) {
    object InvalidDescriptor : PaymentActionException("invalidDescriptor")
    object InvalidRequest : PaymentActionException("invalidRequest")
    object InvalidResponse : PaymentActionException("invalidResponse")
    object Transport : PaymentActionException("transport")
    object Storage : PaymentActionException("storage")
    object AlreadyAttempted : PaymentActionException("alreadyAttempted")
    class Http(val status: Int) : PaymentActionException("http($status)")
    class Action(
        val code: PaymentActionFailureCode,
        /** The keys an `INVALID_REQUEST` names and why, never their values. */
        val fieldErrors: List<ActionFieldError> = emptyList(),
    ) : PaymentActionException("action:${code.name}")

    override fun toString(): String = label
}

/** Only the public action error vocabulary is retained; response bodies are never attached. */
internal enum class PaymentActionFailureCode(val httpStatus: Int) {
    INVALID_REQUEST(400),
    AUTHORIZATION_REQUIRED(401),
    STATE_NOT_FOUND(404),
    REQUEST_CONFLICT(409),
    ORDER_STATE_CHANGED(409),
    CONCURRENT_STATE_CHANGE(409),
    OPERATION_IN_FLIGHT(425),
    PROVIDER_REJECTED(422),
    PROVIDER_RATE_LIMITED(429),
    PROVIDER_UNAVAILABLE(502),
    INVALID_PROVIDER_RESPONSE(502),
    OUTCOME_UNKNOWN(503),
    ;

    companion object {
        /** A known `{version: 1, code}` whose code belongs to [status], else [PaymentActionException.Http]. */
        fun decode(body: ByteArray?, status: Int): PaymentActionException {
            val json = body?.let(::decodeActionObject) ?: return PaymentActionException.Http(status)
            val code = entries.firstOrNull { it.name == json["code"] }
            if (strictInt(json["version"]) != 1 || code == null || code.httpStatus != status) {
                return PaymentActionException.Http(status)
            }
            if (code != INVALID_REQUEST) return PaymentActionException.Action(code)
            return PaymentActionException.Action(code, fieldErrors(json["fieldErrors"]))
        }

        private fun fieldErrors(raw: Any?): List<ActionFieldError> = (raw as? List<*>).orEmpty().mapNotNull { entry ->
            val error = entry as? Map<*, *> ?: return@mapNotNull null
            val key = (error["key"] as? String)?.takeIf { it.length in 1..MAX_FIELD_KEY_LENGTH } ?: return@mapNotNull null
            val reason = (error["reason"] as? String)?.takeIf { it.length in 1..MAX_FIELD_KEY_LENGTH } ?: return@mapNotNull null
            ActionFieldError(key, reason)
        }

        private const val MAX_FIELD_KEY_LENGTH = 256
    }
}

/** A field a request got wrong: its key and the reason (`REQUIRED`, `INVALID`, `UNSUPPORTED` or a newer one). */
internal data class ActionFieldError(val key: String, val reason: String)
