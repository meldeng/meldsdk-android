package io.meld.sdk.wallet

import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.actions.strictInt
import java.net.URI
import java.net.URISyntaxException

/** A wallet action's answer. Only exact status and next-step pairs are recognized. */
internal class WalletActionResponse private constructor(
    val state: State,
    /** False when the status or its step is one this build does not know. The state is then [State.UNKNOWN]. */
    val recognized: Boolean,
    val verification: WalletVerification?,
) {
    enum class State(val nextStep: String) {
        NOT_STARTED("NONE"),
        SUBMITTED("WAIT_FOR_PAYMENT"),
        VERIFICATION_REQUIRED("OPEN_HOSTED_VERIFICATION"),
        IN_PROGRESS("WAIT_FOR_PROVIDER"),
        UNKNOWN("WAIT_FOR_PROVIDER"),
        EXPIRED("WAIT_FOR_PAYMENT"),
        FAILED("NONE"),
    }

    companion object {
        /** The answer in [json], or [PaymentActionException.InvalidResponse] when it is malformed. */
        fun decode(json: Map<String, Any?>): WalletActionResponse {
            if (strictInt(json["version"]) != 1) throw PaymentActionException.InvalidResponse
            val status = json["status"] as? String ?: throw PaymentActionException.InvalidResponse
            val nextStep = json["nextStep"] as? String ?: throw PaymentActionException.InvalidResponse
            val state = State.entries.firstOrNull { it.name == status }
            if (state == null || state.nextStep != nextStep) return WalletActionResponse(State.UNKNOWN, false, null)
            if (state == State.VERIFICATION_REQUIRED) {
                val verification = json["verification"] as? Map<*, *> ?: throw PaymentActionException.InvalidResponse
                return WalletActionResponse(state, true, WalletVerification.decode(verification))
            }
            if (json["verification"] != null) throw PaymentActionException.InvalidResponse
            return WalletActionResponse(state, true, null)
        }
    }
}

/** A hosted verification the customer may open once. Redacted: its URL is the provider's session. */
internal class WalletVerification private constructor(
    /** Opened unchanged; never part of a diagnostic. */
    val url: URI,
    val disposition: Disposition,
    val expiresAtMillis: Long,
) {
    enum class Disposition { RESUME_PAYMENT, REPLACE_PAYMENT_IN_HOSTED_FLOW }

    override fun toString(): String = "WalletVerification[REDACTED]"

    companion object {
        fun decode(json: Map<*, *>): WalletVerification {
            val url = try {
                URI(json["url"] as? String ?: throw PaymentActionException.InvalidResponse)
            } catch (_: URISyntaxException) {
                throw PaymentActionException.InvalidResponse
            }
            if (url.scheme != "https" || url.host == null || url.rawUserInfo != null || (url.port != -1 && url.port != 443)) {
                throw PaymentActionException.InvalidResponse
            }
            val disposition = Disposition.entries.firstOrNull { it.name == json["paymentDisposition"] }
                ?: throw PaymentActionException.InvalidResponse
            val expiresAt = (json["expiresAt"] as? String)?.let(::parseRfc3339) ?: throw PaymentActionException.InvalidResponse
            return WalletVerification(url, disposition, expiresAt)
        }
    }
}
