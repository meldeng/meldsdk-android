package io.meld.sdk.hosted

import android.util.Log
import android.view.ViewGroup
import androidx.webkit.WebViewFeature
import io.meld.sdk.AdapterPresentation
import io.meld.sdk.MeldAdapter
import io.meld.sdk.MeldCapabilities
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldHeadlessPresentation
import io.meld.sdk.MeldMountException
import io.meld.sdk.MeldOrder
import io.meld.sdk.MeldProviderSession
import io.meld.sdk.WebViewHost
import io.meld.sdk.isRegisteredWidgetUrl

/**
 * A wallet the provider presents from its own payment link. The page runs offscreen, its pay button is
 * pressed once, and the wallet's sheet is all the customer sees. Generic over the declared protocol:
 * what each provider's page says lives in [HostedLinkProfiles].
 */
internal class HostedWalletLinkAdapter(
    profiles: List<HostedLinkProfile> = HostedLinkProfiles.all,
    private val platformSupported: () -> Boolean = ::webViewHostsWalletPages,
) : MeldAdapter {
    private val profiles = profiles.associateBy { it.protocol }

    override val label = "Provider-hosted wallet link (PROVIDER_HOSTED)"

    override val presentations = this.profiles.values.flatMap { profile ->
        profile.presentScripts.keys.map { method ->
            AdapterPresentation(method, MeldHeadlessPresentation(SURFACE, profile.protocol, profile.version))
        }
    }

    override val capabilities: MeldCapabilities
        get() = if (platformSupported()) {
            MeldCapabilities(embeddable = false, surface = "native-wallet", requiresUserGesture = true)
        } else {
            MeldCapabilities(embeddable = false, surface = "unsupported", requiresUserGesture = false)
        }

    override fun matches(paymentMethodType: String?, renderMode: String?, widgetUrl: String?): Boolean = false

    override fun mount(order: MeldOrder, host: ViewGroup, handlers: MeldEventHandlers): MeldProviderSession {
        if (!platformSupported()) {
            throw MeldMountException.Unsupported("This device's WebView cannot present a provider-hosted wallet.")
        }
        val profile = order.headlessPresentation?.let { profiles[it.protocol] }
            ?: throw MeldMountException.Unsupported("Order declares no provider-hosted protocol this SDK knows.")
        val script = profile.presentScripts[order.paymentMethodType]
            ?: throw MeldMountException.Unsupported("${profile.protocol} does not present ${order.paymentMethodType}.")
        val link = paymentLink(order) ?: throw MeldMountException.MissingWidgetUrl
        if (!isRegisteredWidgetUrl(link, profile.origins)) {
            throw MeldMountException.Unsupported("Invalid widget origin for declared presentation.")
        }
        val surface = OffscreenSurfaceHost.attach(host) ?: throw MeldMountException.PresentationUnavailable

        val session = HostedWalletSession(order.id, handlers, profile, script)
        val page = WebViewHost(
            url = link,
            orderId = order.id,
            handlers = MeldEventHandlers(onError = session::pageFailed),
            allowedOrigins = profile.origins,
            firesReadyOnNavigation = false,
            nativeMessageChannels = setOf(profile.channel),
            mainFrameHosts = profile.hosts,
            paymentRequestEnabled = true,
            onRenderProcessGone = session::pageTerminated,
        ) { message ->
            session.receive(message)
            emptyList()
        }
        session.start(page, surface)
        return session
    }

    companion object {
        private const val SURFACE = "PROVIDER_HOSTED"
        private const val TAG = "MeldSDK"

        /** `surface.url` where the contract is headed, else today's `paymentLinkUrl`. */
        internal fun paymentLink(order: MeldOrder): String? {
            val details = order.paymentMethodResponseDetails ?: return null
            val surfaceUrl = (details["surface"] as? Map<*, *>)?.get("url") as? String
            if (!surfaceUrl.isNullOrEmpty()) return surfaceUrl
            return (details["paymentLinkUrl"] as? String)?.takeIf { it.isNotEmpty() }
        }

        private fun webViewHostsWalletPages(): Boolean =
            try {
                WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST) &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            } catch (e: LinkageError) {
                Log.w(TAG, "the WebView provider did not load; provider-hosted wallets are unavailable", e)
                false
            }
    }
}
