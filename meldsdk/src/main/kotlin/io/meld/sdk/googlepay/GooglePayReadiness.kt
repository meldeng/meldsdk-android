package io.meld.sdk.googlepay

import android.content.Context
import com.google.android.gms.wallet.IsReadyToPayRequest
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.WalletConstants
import io.meld.sdk.Meld
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldErrorCode
import io.meld.sdk.MeldReadiness
import io.meld.sdk.MeldReadinessReason
import io.meld.sdk.providerEnvironment
import org.json.JSONArray
import org.json.JSONObject

/** The card readiness a payment surface's Google Pay request asks of the device. */
internal data class GooglePayReadinessSpec(
    val allowedAuthMethods: List<String>,
    val allowedCardNetworks: List<String>,
    val existingPaymentMethodRequired: Boolean,
) {
    /** An apiVersion 2 IsReadyToPayRequest. Readiness takes no tokenization specification. */
    fun requestJson(): String {
        val parameters = JSONObject()
            .put("allowedAuthMethods", JSONArray(allowedAuthMethods))
            .put("allowedCardNetworks", JSONArray(allowedCardNetworks))
        val card = JSONObject().put("type", "CARD").put("parameters", parameters)
        return JSONObject()
            .put("apiVersion", 2)
            .put("apiVersionMinor", 0)
            .put("allowedPaymentMethods", JSONArray().put(card))
            .put("existingPaymentMethodRequired", existingPaymentMethodRequired)
            .toString()
    }

    companion object {
        /** Any card Google Pay can pay with, for asking before a provider narrows it. */
        val anyCard = GooglePayReadinessSpec(
            allowedAuthMethods = listOf("PAN_ONLY", "CRYPTOGRAM_3DS"),
            allowedCardNetworks = listOf("VISA", "MASTERCARD", "AMEX", "DISCOVER"),
            existingPaymentMethodRequired = true,
        )
    }
}

/** The Wallet call, behind an interface so JVM tests can answer it. */
internal fun interface GooglePayClient {
    fun isReadyToPay(context: Context, requestJson: String, onResult: (Result<Boolean>) -> Unit)
}

internal class GooglePayReadiness(
    private val client: GooglePayClient = PlayServicesGooglePay,
    private val environment: () -> MeldEnvironment = { Meld.environment },
) {
    fun check(context: Context, spec: GooglePayReadinessSpec, onResult: (MeldReadiness) -> Unit) {
        client.isReadyToPay(context, spec.requestJson()) { result ->
            onResult(googlePayReadiness(result, spec, environment()))
        }
    }
}

/**
 * With existingPaymentMethodRequired, Android's single answer is already "ready and a card is present".
 * Only production is asked, so in [MeldEnvironment.SANDBOX] and [MeldEnvironment.QA], where an order
 * may run Google's TEST environment, a missing card is [MeldReadiness.Unknown].
 */
internal fun googlePayReadiness(
    result: Result<Boolean>,
    spec: GooglePayReadinessSpec,
    environment: MeldEnvironment,
): MeldReadiness {
    val ready = result.getOrElse { error ->
        return MeldReadiness.Unknown("Google Play services did not answer: ${error.message ?: error.javaClass.simpleName}")
    }
    return when {
        ready -> MeldReadiness.Ready
        spec.existingPaymentMethodRequired && environment.providerEnvironment == MeldEnvironment.SANDBOX -> MeldReadiness.Unknown(
            "Google Pay reports no card for this payment in production, and a sandbox order may run " +
                "Google's TEST environment, which that answer does not cover.",
        )
        spec.existingPaymentMethodRequired -> MeldReadiness.Unavailable(
            MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE,
            MeldReadinessReason.GOOGLE_PAY_NO_USABLE_CARD,
        )
        else -> MeldReadiness.Unavailable(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, MeldReadinessReason.GOOGLE_PAY_NOT_SUPPORTED)
    }
}

/** Always asks production: the page's environment belongs to an order that does not exist yet. */
internal object PlayServicesGooglePay : GooglePayClient {
    override fun isReadyToPay(context: Context, requestJson: String, onResult: (Result<Boolean>) -> Unit) {
        val options = Wallet.WalletOptions.Builder().setEnvironment(WalletConstants.ENVIRONMENT_PRODUCTION).build()
        Wallet.getPaymentsClient(context.applicationContext ?: context, options)
            .isReadyToPay(IsReadyToPayRequest.fromJson(requestJson))
            .addOnCompleteListener { task ->
                val outcome = if (task.isSuccessful) {
                    Result.success(task.result == true)
                } else {
                    Result.failure(task.exception ?: IllegalStateException("isReadyToPay ended without an answer"))
                }
                onResult(outcome)
            }
    }
}
