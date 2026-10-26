package io.meld.sdk.coinme

import android.view.ViewGroup
import io.meld.sdk.AdapterPresentation
import io.meld.sdk.Meld
import io.meld.sdk.MeldAdapter
import io.meld.sdk.MeldCapabilities
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldMountException
import io.meld.sdk.MeldOrder
import io.meld.sdk.MeldProviderSession

/**
 * Coinme card (`VENDOR_SDK / COINME_VAULT / 1`), presented by the Meld web SDK's Coinme flow in a
 * WebView ([CoinmeWebCheckout]): Coinme's web Vault fields capture the card and its web Risk SDK scores
 * the device, so no Coinme library ships in this SDK. Settlement arrives by webhook.
 */
internal class CoinmeCardAdapter : MeldAdapter {
    override val label = "Coinme card (CREDIT_DEBIT_CARD / VENDOR_SDK COINME_VAULT)"

    override val presentations = listOf(AdapterPresentation(CoinmeOrder.PAYMENT_METHOD, CoinmeOrder.PRESENTATION))

    override val capabilities = MeldCapabilities(embeddable = true, surface = "embedded", requiresUserGesture = false)

    override fun matches(paymentMethodType: String?, renderMode: String?, widgetUrl: String?): Boolean = false

    override fun acceptsDeclaredOrder(order: MeldOrder): Boolean = CoinmeOrder.parse(order, Meld.environment) != null

    override fun mount(order: MeldOrder, host: ViewGroup, handlers: MeldEventHandlers): MeldProviderSession {
        val coinme = CoinmeOrder.parse(order, Meld.environment)
            ?: throw MeldMountException.Unsupported("Order is not a complete COINME_VAULT card order for this environment.")
        return CoinmeWebCheckout(coinme, order.raw, Meld.environment, handlers).present(host)
    }
}
