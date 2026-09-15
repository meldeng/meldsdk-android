package io.meld.sdk.banxa

import android.view.ViewGroup
import io.meld.sdk.MeldCapabilities
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldProviderSession

/**
 * How a Banxa order's capture surface is put on screen, given the Primer client token the order
 * carries.
 *
 * This seam exists because the *right* answer differs by platform and is still moving. Banxa's own
 * mobile SDK is disqualified — it creates its own order (`startPayment` "checks eligibility, creates
 * the order, and presents the native payment sheet"), which would leave Meld without a
 * `headless_order` row to correlate, and it requires Banxa credentials on the device. That leaves
 * two presenters for the same token:
 *
 * - [BanxaWebCheckoutPresenter] — Banxa's `<banxa-primer-checkout>` web component, run inside the
 *   shared `WebViewHost`. Works today, and is what card ships on.
 * - a Primer-native presenter — Primer's own Android SDK, which completes a payment on an order that
 *   already exists. A true native sheet, native 3DS, no vendored bundle, and the only route that can
 *   present **Google Pay / Apple Pay-equivalent wallets**: the web component cannot, because a wallet
 *   in a WebView needs the page origin to be one registered with the processor, and the bootstrap
 *   page's origin is not ours.
 *
 * Keeping the choice behind this interface means adding the native presenter is one new file plus a
 * dependency, with the working web path untouched — rather than a rewrite of the adapter, its
 * selection, or its event mapping.
 */
internal interface BanxaCheckoutPresenter {

    /**
     * What this surface can do, surfaced as the adapter's own [MeldCapabilities]. It belongs to the
     * presenter, not the adapter: the web component is an embedded widget that needs no user gesture,
     * whereas Primer's drop-in presents its own modal and is not embeddable at all. An integrator
     * guards on `capabilities.embeddable`, so answering for the wrong presenter would have it lay out
     * a container for a sheet that never fills it.
     */
    val capabilities: MeldCapabilities

    /**
     * Present the capture surface for [clientToken], wiring its lifecycle to [handlers].
     *
     * @param host the view group an embedded surface renders into. A presenter that presents itself
     *   (a native sheet) may ignore it beyond taking its `context`.
     */
    fun present(
        clientToken: String,
        orderId: String?,
        host: ViewGroup,
        handlers: MeldEventHandlers,
    ): MeldProviderSession
}
