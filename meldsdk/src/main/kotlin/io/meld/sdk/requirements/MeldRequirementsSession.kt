package io.meld.sdk.requirements

import io.meld.sdk.MeldEnvironment
import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.toMap
import org.json.JSONException
import org.json.JSONObject

/**
 * The requirements session your backend minted with `POST /crypto/onramp/requirements-sessions`, passed
 * through unchanged. It carries the session's bearer, so it never prints it.
 */
class MeldRequirementsSession private constructor(
    /** The session id. */
    val id: String,
    /** The mint response as decoded, so the declared bearer pointer resolves against all of it. */
    internal val raw: Map<String, Any?>,
) {
    override fun toString(): String = "[MeldRequirementsSession REDACTED]"

    companion object {
        /** Decode the mint response your backend returns (pass it through untouched). */
        @JvmStatic
        @Throws(MeldRequirementsSessionException::class)
        fun fromJson(json: String): MeldRequirementsSession {
            val raw = try {
                JSONObject(json).toMap()
            } catch (e: JSONException) {
                throw MeldRequirementsSessionException.Malformed
            }
            return fromMap(raw)
        }

        /**
         * Valid against any environment's host. The mint's endpoint is relative, so the session runs against the
         * environment configured when it runs, and the SDK cannot tell which one minted it.
         */
        internal fun fromMap(raw: Map<String, Any?>): MeldRequirementsSession {
            val descriptor = MeldEnvironment.entries.firstNotNullOfOrNull { environment ->
                try {
                    RequirementsSessionDescriptor.parse(raw, environment)
                } catch (_: PaymentActionException) {
                    null
                }
            } ?: throw MeldRequirementsSessionException.Invalid
            return MeldRequirementsSession(descriptor.sessionId, raw)
        }
    }
}

sealed class MeldRequirementsSessionException(message: String) : Exception(message) {
    /** The JSON was not a JSON object. */
    object Malformed : MeldRequirementsSessionException("Requirements session JSON is not a JSON object.")

    /** The object is not a requirements session this SDK can run. Pass the mint response through unchanged. */
    object Invalid : MeldRequirementsSessionException(
        "Requirements session has no valid requirementsActions. Pass the mint response through unchanged.",
    )
}
