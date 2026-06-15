package io.meld.sdk

import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting

/**
 * Mercuryo credit/debit card, rendered by loading the signed widget URL in a WebView. Maps
 * Mercuryo's widget messages onto the Meld event model:
 *
 *   mercuryoReady           -> Ready
 *   mercuryoPaymentFinished -> PaymentSubmitted (UX hint — user finished the flow, NOT settlement)
 *   mercuryoStatusChanged   -> StatusChange with a normalized status; terminal `failed`
 *                              additionally -> Error, terminal `cancelled` -> Cancel
 */
internal class MercuryoCardAdapter : MeldAdapter {
    override val label = "Mercuryo card (CREDIT_DEBIT_CARD / IFRAME)"
    override val capabilities =
        MeldCapabilities(embeddable = true, surface = "embedded", requiresUserGesture = false)

    override fun matches(paymentMethodType: String?, renderMode: String?): Boolean =
        paymentMethodType == "CREDIT_DEBIT_CARD" && renderMode == "IFRAME"

    override fun mount(
        order: MeldOrder,
        host: ViewGroup,
        handlers: MeldEventHandlers,
    ): MeldProviderSession {
        val urlString = order.paymentMethodResponseDetails?.serviceProviderWidgetUrl
            ?: throw MeldMountException.MissingWidgetUrl

        warnIfEnvironmentMismatch(Uri.parse(urlString).host)

        val session = WebViewHost(
            url = urlString,
            orderId = order.id,
            handlers = handlers,
            allowedOrigins = allowedOrigins,
        ) { message -> interpret(message, order.id) }
        session.mount(host)
        return session
    }

    private fun warnIfEnvironmentMismatch(widgetHost: String?) {
        if (widgetHost == null) return
        val orderEnv = hostsByEnvironment.entries
            .firstOrNull { it.value.contains(widgetHost) }?.key ?: return
        if (orderEnv != Meld.environment) {
            Log.w(
                TAG,
                "order environment is '${orderEnv.raw}' but Meld.configure set " +
                    "'${Meld.environment.raw}'. Configure the matching environment to silence this.",
            )
        }
    }

    companion object {
        private const val TAG = "MeldSDK"

        // MARK: - Mercuryo message -> Meld events

        @VisibleForTesting
        internal fun interpret(providerMessage: Map<String, Any?>, orderId: String?): List<MeldEvent> {
            val type = providerMessage["type"] as? String ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val payload = providerMessage["data"] as? Map<String, Any?>

            return when (type) {
                "mercuryoReady" -> listOf(MeldEvent.Ready)

                "mercuryoPaymentFinished" -> listOf(MeldEvent.PaymentSubmitted)

                "mercuryoStatusChanged" -> {
                    val code = payload?.get("status") as? String
                    val status = normalize(code)
                    val events = mutableListOf<MeldEvent>(
                        MeldEvent.StatusChange(
                            MeldStatusChange(
                                orderId = orderId,
                                status = status,
                                providerStatus = code,
                                raw = payload ?: providerMessage,
                            ),
                        ),
                    )
                    when (status) {
                        MeldStatus.FAILED -> events.add(
                            MeldEvent.Error(
                                MeldError(
                                    orderId = orderId,
                                    code = code ?: "failed",
                                    message = "Mercuryo reported terminal status: ${code ?: "failed"}",
                                    recoverable = false,
                                ),
                            ),
                        )
                        MeldStatus.CANCELLED -> events.add(MeldEvent.Cancel)
                        else -> {}
                    }
                    events
                }

                else -> emptyList() // non-lifecycle provider messages
            }
        }

        // Mercuryo's status vocabulary -> the SDK's normalized set. Interim/unknown codes
        // (new, pending, processing, …) collapse to PENDING.
        private val statusMap: Map<String, MeldStatus> = mapOf(
            "paid" to MeldStatus.COMPLETED, "completed" to MeldStatus.COMPLETED,
            "order_completed" to MeldStatus.COMPLETED, "succeeded" to MeldStatus.COMPLETED,
            "success" to MeldStatus.COMPLETED,
            "failed" to MeldStatus.FAILED, "order_failed" to MeldStatus.FAILED,
            "failed_exchange" to MeldStatus.FAILED, "descriptor_failed" to MeldStatus.FAILED,
            "rejected" to MeldStatus.FAILED,
            "cancelled" to MeldStatus.CANCELLED, "canceled" to MeldStatus.CANCELLED,
        )

        @VisibleForTesting
        internal fun normalize(code: String?): MeldStatus {
            if (code.isNullOrEmpty()) return MeldStatus.PENDING
            return statusMap[code] ?: MeldStatus.PENDING
        }

        // MARK: - Environment sanity check

        // Recognized widget URL hosts per environment. The order's signed URL is authoritative;
        // this lets the SDK flag a configured environment that doesn't match the order.
        private val hostsByEnvironment: Map<MeldEnvironment, Set<String>> = mapOf(
            MeldEnvironment.SANDBOX to setOf("sandbox-widget.mrcr.io", "sandbox-exchange.mrcr.io"),
            MeldEnvironment.PRODUCTION to setOf("widget.mercuryo.io", "exchange.mercuryo.io"),
        )

        // Provider origins the bridge trusts for postMessage. All known Mercuryo hosts across
        // environments — the order's signed URL already pins which one actually loads.
        internal val allowedOrigins: Set<String> =
            hostsByEnvironment.values.flatten().map { "https://$it" }.toSet()
    }
}
