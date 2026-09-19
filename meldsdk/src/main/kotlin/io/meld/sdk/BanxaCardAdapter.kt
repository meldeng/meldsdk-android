package io.meld.sdk

import android.view.ViewGroup
import io.meld.sdk.banxa.BanxaCheckoutPresenter
import io.meld.sdk.banxa.BanxaWebCheckoutPresenter

/**
 * Banxa credit/debit card. Single-step: the backend has already created the Banxa order, and the
 * order carries a Primer client token (Banxa's `nativeToken`) as `sdkSessionToken`. There is no
 * provider URL to load — the capture surface is built on the device from the token alone, which
 * makes this the first adapter with no `serviceProviderWidgetUrl`.
 *
 * Rendering runs Banxa's own `<banxa-primer-checkout>` web component (which wraps Primer's Checkout
 * Web SDK) inside the reused [WebViewHost], the same shape as [UpholdCardAdapter]. That is Banxa's
 * documented order-first integration: the component takes only a client token, so no Banxa API
 * credential ever reaches the device. Banxa's *native* Android SDK is deliberately not used — it
 * creates the Banxa order inside the call that presents the sheet, which would make the device the
 * order's origin; Meld creates every Banxa order server-side and hands this adapter one to pay.
 */
internal class BanxaCardAdapter(
    /**
     * How the capture surface is presented. Injected so the native (Primer SDK) presenter can be
     * swapped in without touching selection or event mapping — see [BanxaCheckoutPresenter].
     */
    internal val presenter: BanxaCheckoutPresenter = BanxaWebCheckoutPresenter(),
) : MeldAdapter {

    override val presentations = listOf(AdapterPresentation(
        "CREDIT_DEBIT_CARD", MeldHeadlessPresentation("EMBEDDED_WIDGET", "BANXA_CHECKOUT", 1),
    ))

    override val label: String = "Banxa card (CREDIT_DEBIT_CARD / IFRAME, SDK token)"

    override val capabilities: MeldCapabilities get() = presenter.capabilities

    /**
     * Never consulted for dispatch (the order-level [matches] below wins), but the interface requires
     * it: without the detail fields the only truthful answer is "cannot tell from these three".
     */
    override fun matches(paymentMethodType: String?, renderMode: String?, widgetUrl: String?): Boolean = false

    /**
     * Banxa has no widget URL at all, so the three-discriminator [matches] cannot identify it — this
     * legacy adapter matches on the order and keys on the provider itself. Declared protocols use
     * [presentations] instead.
     *
     * Provider identity, not rendering technology: an earlier revision matched on
     * `sdkSessionFlow == "primer"`, which overloaded a field that means "which step of Uphold's
     * two-step flow this is" and, worse, would have matched every other Primer-backed provider too —
     * Primer is an orchestrator several PSPs sit behind. [MeldOrder.serviceProvider] is the thing an
     * adapter is actually selected by.
     *
     * Deliberately not also gated on the presence of `sdkSessionToken`. A Banxa order without one is
     * still Banxa's. Claiming it and failing in [mount] names the missing token instead of reporting
     * an unknown adapter. Other legacy widget adapters require their own registered origins.
     */
    override fun matches(order: MeldOrder): Boolean =
        order.serviceProvider == SERVICE_PROVIDER &&
            order.paymentMethodType == "CREDIT_DEBIT_CARD" &&
            order.paymentMethodResponseDetails?.renderMode == "IFRAME"

    /**
     * Headless card: Primer's fields render in place from the order's client token
     * (`sdkSessionToken`), which Banxa returns as `nativeToken` for merchants provisioned for native
     * payments.
     *
     * There is deliberately no fallback to `serviceProviderWidgetUrl`. For Banxa that URL is its full
     * hosted checkout — sign-in, email OTP, KYC, method picker — a different product rather than a
     * lesser rendering of this one, and mounting it here would silently turn a headless integration
     * into a hosted one. A missing token is a provisioning fault, and says so.
     */
    override fun mount(
        order: MeldOrder,
        host: ViewGroup,
        handlers: MeldEventHandlers,
    ): MeldProviderSession {
        val clientToken = (order.paymentMethodResponseDetails?.get("sdkSessionToken") as? String)
            ?.takeIf { it.isNotEmpty() }
            ?: throw MeldMountException.Unsupported(
                "Banxa card order is missing sdkSessionToken, the Primer client token the checkout mounts " +
                    "from. The backend did not receive one from Banxa for this order.",
            )
        return presenter.present(clientToken, order.id, host, handlers)
    }

    internal companion object {
        internal const val SERVICE_PROVIDER = "BANXA"
    }
}
