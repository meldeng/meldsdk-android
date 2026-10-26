package io.meld.sdk.coinme

import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldHeadlessPresentation
import io.meld.sdk.MeldOrder
import io.meld.sdk.actions.PaymentActionDescriptor

/** A `VENDOR_SDK / COINME_VAULT / 1` card order, admitted only with everything the flow needs. */
internal class CoinmeOrder private constructor(
    val id: String,
    val live: Boolean,
    val actions: PaymentActionDescriptor,
) {
    override fun toString(): String = "CoinmeOrder[REDACTED]"

    companion object {
        val PRESENTATION = MeldHeadlessPresentation("VENDOR_SDK", "COINME_VAULT", 1)
        const val PAYMENT_METHOD = "CREDIT_DEBIT_CARD"
        const val CREATE_CUSTOMER_AUTH_TOKEN = "CREATE_CUSTOMER_AUTH_TOKEN"
        const val CREATE_PAYMENT_SESSION = "CREATE_PAYMENT_SESSION"

        fun parse(order: MeldOrder, environment: MeldEnvironment): CoinmeOrder? {
            if (order.headlessPresentation != PRESENTATION || order.paymentMethodType != PAYMENT_METHOD) return null
            val id = order.id?.takeIf { it.isNotEmpty() } ?: return null
            val details = order.paymentMethodResponseDetails ?: return null
            val data = details["sdkSessionData"] as? Map<*, *> ?: return null
            val live = when (data["environment"]) {
                "sandbox" -> false
                "live" -> true
                else -> return null
            }
            if (live != (environment == MeldEnvironment.PRODUCTION)) return null
            val actions = PaymentActionDescriptor.parse(order, environment) ?: return null
            if (actions.operations[CREATE_CUSTOMER_AUTH_TOKEN] != true || actions.operations[CREATE_PAYMENT_SESSION] != true) {
                return null
            }
            if (data["savedCards"].let { it != null && it !is List<*> } ||
                listOf("customerId", "paymentProviderId", "riskClientId", "riskPartnerId").any { text(data[it]) == null } ||
                text(details["sdkSessionToken"]) == null
            ) {
                return null
            }
            return CoinmeOrder(id, live, actions)
        }

        private fun text(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }
    }
}
