package io.meld.sdk.banxa

import android.content.Context
import android.view.ViewGroup
import io.meld.sdk.MeldCapabilities
import io.meld.sdk.MeldError
import io.meld.sdk.MeldEvent
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldMountException
import io.meld.sdk.MeldProviderSession
import io.meld.sdk.WebViewHost
import org.json.JSONArray

/**
 * Presents Banxa's `<banxa-primer-checkout>` web component inside the shared [WebViewHost].
 *
 * The component takes a client token and nothing else, so no Banxa credential reaches the device and
 * the order stays Meld's. Banxa's own Android SDK is not yet integrated here — not a rejection of it:
 * the iOS SDK presents Banxa through Primer headlessly (
 * externalOrderId linking answers the "it creates its own order" objection), and Android should follow
 * the same shape when the Android SDK is wired in.
 *
 * Card only. A wallet sheet cannot be presented from here — a WebView wallet requires the page origin
 * to be one registered with the processor, and the bootstrap page's origin is Primer's, not ours to
 * register. Wallets need the Primer-native presenter.
 */
internal class BanxaWebCheckoutPresenter : BanxaCheckoutPresenter {

    override val capabilities: MeldCapabilities =
        MeldCapabilities(embeddable = true, surface = "embedded", requiresUserGesture = false)

    override fun present(
        clientToken: String,
        orderId: String?,
        host: ViewGroup,
        handlers: MeldEventHandlers,
    ): MeldProviderSession {
        val bundle = loadBundle(host.context)
        val theme = loadTheme(host.context)
        val session = WebViewHost(
            url = PAGE_ORIGIN,
            orderId = orderId,
            handlers = handlers,
            allowedOrigins = ALLOWED_ORIGINS,
            htmlContent = bootstrapHtml(bundle, theme, clientToken),
            // The bootstrap is local HTML, so it finishes loading almost immediately — long before
            // Primer has fetched its configuration. Letting that count as ready fired onReady on an
            // empty frame and the one-shot latch then discarded the real banxa:ready, so an expired
            // client token looked like a widget that was up and simply blank.
            firesReadyOnNavigation = false,
        ) { message -> interpret(message, orderId) }
        session.mount(host)
        return session
    }

    /**
     * Primer's components carry their own layout CSS but ship NO theme: every rule reads
     * `var(--primer-...)`, and neither the npm package nor the Banxa bundle attaches the block that
     * defines those tokens. Undefined, they collapse — `gap: var(--primer-space-medium)` becomes zero
     * — and the card form renders as bare labels and unstyled inputs. Pinned like the bundle, and for
     * the same reason: it is vendored third-party content inside a WebView with a native bridge.
     */
    private fun loadTheme(context: Context): String {
        cachedTheme?.let { return it }
        val bytes = try {
            context.assets.open(THEME_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            throw MeldMountException.Unsupported("Banxa checkout theme ($THEME_ASSET) is missing from SDK assets.")
        }
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actual != EXPECTED_THEME_SHA256) {
            throw MeldMountException.Unsupported(
                "Banxa checkout theme failed its pinned integrity check " +
                    "(expected $EXPECTED_THEME_SHA256, got $actual); refusing to execute.",
            )
        }
        val css = String(bytes, Charsets.UTF_8)
        cachedTheme = css
        return css
    }

    private fun loadBundle(context: Context): String {
        cachedBundle?.let { return it }
        val bytes = try {
            context.assets.open(BUNDLE_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            throw MeldMountException.Unsupported("Banxa checkout SDK bundle ($BUNDLE_ASSET) is missing from SDK assets.")
        }
        verifyBundleIntegrity(bytes)
        val js = String(bytes, Charsets.UTF_8)
        cachedBundle = js
        return js
    }

    // Pin the vendored bundle by content hash and refuse to execute anything else. The bundle owns the
    // checkout's postMessage/bridge security surface, so a swapped or tampered asset must fail closed
    // rather than run inside the WebView with a native bridge attached.
    private fun verifyBundleIntegrity(bytes: ByteArray) {
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actual != EXPECTED_BUNDLE_SHA256) {
            throw MeldMountException.Unsupported(
                "Banxa checkout SDK bundle failed its pinned integrity check " +
                    "(expected $EXPECTED_BUNDLE_SHA256, got $actual); refusing to execute.",
            )
        }
    }

    internal companion object {
        internal const val BUNDLE_ASSET = "banxa-primer-checkout.bundle.js"
        internal const val THEME_ASSET = "banxa-primer-theme.css"

        // SHA-256 of Primer's default design-token theme, extracted verbatim from the pinned bundle
        // (@primer-io/primer-js 1.9.0). Byte-identical to the iOS SDK's copy, deliberately.
        internal const val EXPECTED_THEME_SHA256 =
            "259f773f44fe551b92c575b22a4e112337b88a2af3770f879160bcc5da20771b"

        // SHA-256 of the pinned vendored bundle (esbuild IIFE of
        // @banxa-official/javascript-native-payments-sdk/web 1.0.1 + @primer-io/primer-js 1.9.0,
        // global `MeldBanxaCheckout`). Update deliberately — and re-review — when the bundle is
        // intentionally revved; drift fails the mount rather than silently running new checkout code.
        internal const val EXPECTED_BUNDLE_SHA256 =
            "031c67d4851f8e5ba38b0871888bd70ebb641618b04e04369cfed9e9fb59eb7e"

        /**
         * Base URL for the bootstrap page, i.e. the origin the page claims.
         *
         * A Meld origin, deliberately NOT Primer's. An earlier revision used `https://sdk.primer.io`
         * to keep the page same-origin with the SDK it loads, which turned out to be the one thing it
         * must not be: the vendored bundle mounts Primer's hosted card inputs and its api-controller
         * from that same origin, and the JS bridge runs in every frame. Same-origin plus a script in
         * every frame means `iframe.contentDocument` — PAN and CVV — and the api-controller's access
         * token are readable from page context. That is the isolation Primer's hosted fields exist to
         * provide, and the basis of the SAQ-A argument that card data never reaches our systems.
         *
         * Nothing is served from this URL and nothing is fetched from it: it is only the origin the
         * page claims when the HTML is loaded. It has to be https (Primer requires a secure context)
         * and it has to be ours, so it cannot collide with a real origin someone else controls.
         *
         * Primer does not need the parent same-origin — on a merchant site it never is, which is the
         * configuration its SDK is built for. Verified cross-origin on the iOS simulator: the bundle
         * executes, the custom element upgrades and Primer's own element registers, unchanged. Its
         * hosts stay in [ALLOWED_ORIGINS] so its subframes can still reach the bridge.
         *
         * Card only. Apple Pay additionally requires the page origin to be a domain registered with
         * the processor for Apple Pay domain verification, which this is not; that belongs to the
         * Apple Pay phase rather than being quietly inherited here.
         */
        internal const val PAGE_ORIGIN = "https://banxa-checkout.sdk.meld.io"

        /**
         * Primer serves the checkout, its hosted card inputs, its assets and its analytics from
         * distinct hosts; all are origins the bootstrap page legitimately talks to.
         */
        internal val ALLOWED_ORIGINS = setOf(
            "https://sdk.primer.io",
            "https://sdk.production.primer.io",
            "https://assets.primer.io",
            "https://assets.production.core.primer.io",
        )

        @Volatile
        private var cachedBundle: String? = null
        private var cachedTheme: String? = null

        /**
         * HTML that registers `<banxa-primer-checkout>`, hands it the client token, and relays the
         * component's `banxa:*` events to native through `window.meldSendToNativeApp`.
         *
         * The component re-dispatches every Primer event under a `banxa:` prefix, so the vocabulary
         * here is Primer's: `ready`, `payment-start`, `payment-success`, `payment-failure`,
         * `payment-cancel`, plus card-level `card-error` (inline field validation, deliberately not
         * surfaced — see [interpret]).
         */
        internal fun bootstrapHtml(bundleJs: String, themeCss: String, clientToken: String): String {
            // Guard against a literal </script> inside the bundle prematurely closing the tag.
            val safeBundle = bundleJs.replace("</script", "<\\/script")
            // The same hazard one tag over: a `</style` sequence would close the block early and spill
            // the rest into the document.
            val safeTheme = themeCss.replace("</style", "<\\/style")
            // JSON-encode the token so a quote or a </script> inside it cannot break out of the script.
            val tokenJson = JSONArray().put(clientToken).toString()
            return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>$safeTheme</style>
            <style>html,body{margin:0;padding:0;height:100%;width:100%;background:#fff;
            font:15px/1.4 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}
            /* The WebView is edge-to-edge inside the host view, so the page owns its own gutter. */
            #meld-banxa{box-sizing:border-box;width:100%;min-height:100%;padding:16px}</style>
            </head><body><div id="meld-banxa"></div>
            <script>$safeBundle</script>
            <script>
            (function(){
              function post(m){ try{ if(window.meldSendToNativeApp){ window.meldSendToNativeApp({kind:'message',data:m}); } }catch(e){} }
              try {
                var S = window.MeldBanxaCheckout;
                if(!S || !S.registerBanxaPrimerCheckout){ post({type:'error',detail:{error:{code:'sdk_unavailable',message:'Banxa checkout SDK failed to load'}}}); return; }
                S.registerBanxaPrimerCheckout();
                var el = document.createElement('banxa-primer-checkout');
                // Card only. The component's default preset also renders an Apple Pay button, which
                // this WebView can never validate (its page origin is not a registered merchant
                // domain) — the button would show, fail silently in the console, and confuse users.
                el.setAttribute('payment-methods', 'PAYMENT_CARD');
                // Injected into the component's shadow root, where `primer-checkout` lives. Tokens
                // only — this tunes the design system rather than selecting elements, which would be
                // version-fragile across two shadow boundaries. `--primer-size-*` is left alone:
                // despite the name it sizes icons, and raising it stretches the card-network badge.
                el.setAttribute('custom-styles', [
                  'primer-checkout{',
                  '--primer-typography-body-medium-size:15px;',
                  '--primer-typography-body-small-size:13px;',
                  '--primer-space-medium:14px;',
                  '--primer-radius-base:10px;',
                  '--primer-radius-button:12px;',
                  '}'
                ].join(''));
                [
                  'ready','payment-start','payment-success','payment-failure','payment-cancel','card-error'
                ].forEach(function(name){
                  el.addEventListener('banxa:'+name, function(e){ post({type:name, detail:(e?e.detail:null)}); });
                });
                // Not a banxa: event. The component re-dispatches Primer's `primer:*` vocabulary under
                // the banxa: prefix, but an initialization failure — an expired or malformed client
                // token, most often — is raised by the inner primer-checkout as an unprefixed
                // `checkout-error` and never re-dispatched. Without this the component renders its own
                // inline error and native hears nothing at all: no ready, no failure, no way for the
                // host to stop waiting. It bubbles and is composed, so it crosses the shadow boundary.
                el.addEventListener('checkout-error', function(e){
                  var err = e && e.detail && e.detail.error;
                  post({type:'error',detail:{error:{code:'checkout_init_failed',
                    message:String((err&&err.message)||err||'Banxa checkout failed to initialize')}}});
                });
                document.getElementById('meld-banxa').appendChild(el);
                // Set as a property — but the component's setter reflects it to the `client-token`
                // attribute, so the token is in this bootstrap page's DOM regardless. Acceptable here:
                // the page is Meld's own vendored bundle inside the app's WebView, not an integrator page.
                el.clientToken = $tokenJson[0];
              } catch(err){ post({type:'error',detail:{error:{code:'mount_failed',message:String((err&&err.message)||err)}}}); }
            })();
            </script></body></html>
            """.trimIndent()
        }

        internal fun interpret(providerMessage: Map<String, Any?>, orderId: String?): List<MeldEvent> {
            return when ((providerMessage["type"] ?: providerMessage["event"]) as? String) {
                "ready" -> listOf(MeldEvent.Ready)
                // UX hint only. Settlement is confirmed server-side from Banxa's webhook, exactly as
                // for Uphold's 'complete' — the same rule holds across providers.
                "payment-success" -> listOf(MeldEvent.PaymentSubmitted)
                "payment-cancel" -> listOf(MeldEvent.Cancel)
                "payment-failure", "error" -> listOf(MeldEvent.Error(errorFrom(providerMessage, orderId)))
                // Inline field validation (a mistyped CVV, an incomplete expiry). Primer renders these
                // in its own form and the user can correct them, so surfacing them as MeldError would
                // fire onError on ordinary typing.
                "card-error" -> emptyList()
                // 'payment-start' and Primer's state/bin events have no Meld equivalent.
                else -> emptyList()
            }
        }

        private fun errorFrom(providerMessage: Map<String, Any?>, orderId: String?): MeldError {
            val detail = providerMessage["detail"] as? Map<*, *>
            val error = detail?.get("error") as? Map<*, *>
            // Primer's payment-failure detail is {errorCode, errorMessage}; the generic bootstrap error
            // path uses {error:{code,message}}. Accept both rather than losing the reason.
            val code = error?.get("code") as? String
                ?: detail?.get("errorCode") as? String
                ?: "error"
            val message = error?.get("message") as? String
                ?: detail?.get("errorMessage") as? String
                ?: "Banxa checkout error"
            return MeldError(orderId = orderId, code = code, message = message, detail = null, recoverable = false)
        }
    }
}
