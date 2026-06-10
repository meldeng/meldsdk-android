package io.meld.sdk

import android.view.ViewGroup

/**
 * A normalized event the SDK relays to the integrator's [MeldEventHandlers]. A provider adapter
 * produces these from its widget; the host that renders the widget dispatches them.
 */
sealed class MeldEvent {
    object Ready : MeldEvent()
    object PaymentSubmitted : MeldEvent()
    data class StatusChange(val change: MeldStatusChange) : MeldEvent()
    object Cancel : MeldEvent()
    data class Error(val error: MeldError) : MeldEvent()
}

/** A live provider widget mounted into a host view. Call [unmount] to tear it down. */
interface MeldProviderSession {
    fun unmount()
}

/**
 * A provider/render-mode adapter. The SDK is a container manager and event relay; everything
 * provider-specific — which orders it embeds, how its widget is rendered, and how the widget's
 * events map to Meld events — lives behind this interface. Supporting a new provider is a new
 * adapter in the registry, never a change to the public API.
 *
 * A provider that renders by loading a signed URL in a WebView (e.g. Mercuryo) can build on the
 * generic [WebViewHost]; one that renders via its own SDK into a container would mount that
 * instead — the SDK doesn't care which, it just calls [mount].
 */
internal interface MeldAdapter {
    /**
     * Human-readable "(paymentMethodType / renderMode)" this adapter handles. Used to build the
     * "unsupported order" error so it stays truthful as adapters are added.
     */
    val label: String

    /** What this adapter can do with a matching order. */
    val capabilities: MeldCapabilities

    /** Whether this adapter handles the given order discriminators. */
    fun matches(paymentMethodType: String?, renderMode: String?): Boolean

    /**
     * Render the order's widget into the host view, wiring its lifecycle to [handlers], and
     * return a session to tear it down. Throws if the order lacks what this adapter needs.
     */
    @Throws(MeldMountException::class)
    fun mount(order: MeldOrder, host: ViewGroup, handlers: MeldEventHandlers): MeldProviderSession
}
