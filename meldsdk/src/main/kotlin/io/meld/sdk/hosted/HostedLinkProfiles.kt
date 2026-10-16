package io.meld.sdk.hosted

import android.util.Log
import io.meld.sdk.MeldError
import io.meld.sdk.MeldErrorCode
import io.meld.sdk.MeldErrorMessage
import io.meld.sdk.MeldEvent
import io.meld.sdk.MeldStatus
import io.meld.sdk.MeldStatusChange
import io.meld.sdk.toMap
import org.json.JSONException
import org.json.JSONObject

/**
 * What one declared protocol's hosted page needs from the SDK. Every provider literal of the hosted
 * wallet surface lives in this file; [HostedWalletLinkAdapter] and [HostedWalletSession] carry none.
 */
internal class HostedLinkProfile(
    val protocol: String,
    val version: Int,
    /** Exact hosts the payment link, the main frame and the channel are pinned to. */
    val hosts: Set<String>,
    /** The window object the page posts its events to. */
    val channel: String,
    /** Per payment method, a script that presses the page's pay button once and answers 'clicked' or 'missing'. */
    val presentScripts: Map<String, String>,
    private val events: (body: Map<String, Any?>, orderId: String?) -> List<MeldEvent>,
) {
    val origins: Set<String> = hosts.mapTo(LinkedHashSet()) { "https://$it" }

    fun isProviderMessage(message: Map<String, Any?>): Boolean = message["channel"] == channel

    fun interpret(message: Map<String, Any?>, orderId: String?): List<MeldEvent> {
        if (!isProviderMessage(message)) return emptyList()
        val body = (message["body"] as? String)?.let(::parseObject)
        if (body == null) {
            Log.w(TAG, "dropped a malformed message on channel $channel")
            return emptyList()
        }
        return events(body, orderId)
    }
}

internal object HostedLinkProfiles {
    val all: List<HostedLinkProfile> = listOf(
        HostedLinkProfile(
            protocol = "COINBASE_PAYMENT_LINK",
            version = 1,
            hosts = setOf("pay.coinbase.com"),
            channel = "androidWebView",
            presentScripts = mapOf("GOOGLE_PAY" to pressScript("gpay-button-online-api-id")),
            events = ::coinbaseEvents,
        ),
    )
}

private const val TAG = "MeldSDK"

private fun pressScript(buttonId: String): String = """
    (function () {
      var button = document.getElementById('$buttonId');
      if (!button) { return 'missing'; }
      button.click();
      return 'clicked';
    })();
""".trimIndent()

private fun parseObject(json: String): Map<String, Any?>? =
    try {
        JSONObject(json).toMap()
    } catch (_: JSONException) {
        null
    }

/** The page posts `{eventName, data}`; every error is terminal and keeps its event and code in `detail`. */
private fun coinbaseEvents(body: Map<String, Any?>, orderId: String?): List<MeldEvent> {
    val eventName = body["eventName"] as? String ?: return emptyList()
    val data = body["data"] as? Map<*, *>
    return when (eventName) {
        "onramp_api.load_success" -> listOf(MeldEvent.Ready)
        "onramp_api.commit_success", "onramp_api.polling_start" -> listOf(
            MeldEvent.StatusChange(MeldStatusChange(orderId, MeldStatus.PENDING, eventName, body)),
            MeldEvent.PaymentSubmitted,
        )
        "onramp_api.polling_success" ->
            listOf(MeldEvent.StatusChange(MeldStatusChange(orderId, MeldStatus.COMPLETED, eventName, body)))
        "onramp_api.cancel" -> listOf(MeldEvent.Cancel)
        "onramp_api.load_error", "onramp_api.commit_error", "onramp_api.polling_error" -> {
            val providerCode = data?.get("errorCode") as? String
            val code = coinbaseErrorCode(eventName, providerCode)
            val message = MeldErrorMessage.standard(code)
                ?: data?.get("errorMessage") as? String
                ?: "The provider reported an error."
            val detail = providerCode?.let { "$eventName:$it" } ?: eventName
            listOf(MeldEvent.Error(MeldError(orderId, code.raw, message, detail, recoverable = false)))
        }
        else -> {
            Log.d(TAG, "unmapped hosted event $eventName")
            emptyList()
        }
    }
}

private fun coinbaseErrorCode(eventName: String, providerCode: String?): MeldErrorCode = when {
    providerCode == "ERROR_CODE_GUEST_GOOGLE_PAY_NOT_SUPPORTED" -> MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE
    providerCode == "ERROR_CODE_INIT" -> MeldErrorCode.ORDER_STATE_CHANGED
    eventName == "onramp_api.commit_error" -> MeldErrorCode.PAYMENT_REJECTED
    eventName == "onramp_api.polling_error" -> MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN
    else -> MeldErrorCode.PRESENTATION_FAILED
}
