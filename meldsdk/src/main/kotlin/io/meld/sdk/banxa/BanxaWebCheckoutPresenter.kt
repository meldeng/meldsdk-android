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
 * the iOS SDK adopts Banxa's native SDK via `providerOrderCreation = CLIENT` (persist-first +
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
        val session = WebViewHost(
            url = PAGE_ORIGIN,
            orderId = orderId,
            handlers = handlers,
            allowedOrigins = ALLOWED_ORIGINS,
            htmlContent = bootstrapHtml(bundle, clientToken),
        ) { message -> interpret(message, orderId) }
        session.mount(host)
        return session
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

        // SHA-256 of the pinned vendored bundle (esbuild IIFE of
        // @banxa-official/javascript-native-payments-sdk/web 1.0.1 + @primer-io/primer-js 1.9.0,
        // global `MeldBanxaCheckout`). Update deliberately — and re-review — when the bundle is
        // intentionally revved; drift fails the mount rather than silently running new checkout code.
        internal const val EXPECTED_BUNDLE_SHA256 =
            "031c67d4851f8e5ba38b0871888bd70ebb641618b04e04369cfed9e9fb59eb7e"

        /**
         * Base URL for the bootstrap page, i.e. the origin the page claims. Unlike Mercuryo and Uphold
         * there is no provider URL on the order to derive this from, so it is fixed to Primer's SDK
         * origin — the origin the checkout's own assets and iframes come from, which keeps the page
         * same-origin with the SDK it loads.
         *
         * Card only. Apple Pay additionally requires the page origin to be a domain registered with
         * the processor for Apple Pay domain verification, which this is not; that belongs to the
         * Apple Pay phase rather than being quietly inherited here.
         */
        internal const val PAGE_ORIGIN = "https://sdk.primer.io"

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

        /**
         * HTML that registers `<banxa-primer-checkout>`, hands it the client token, and relays the
         * component's `banxa:*` events to native through `window.meldSendToNativeApp`.
         *
         * The component re-dispatches every Primer event under a `banxa:` prefix, so the vocabulary
         * here is Primer's: `ready`, `payment-start`, `payment-success`, `payment-failure`,
         * `payment-cancel`, plus card-level `card-error` (inline field validation, deliberately not
         * surfaced — see [interpret]).
         */
        internal fun bootstrapHtml(bundleJs: String, clientToken: String): String {
            // Guard against a literal </script> inside the bundle prematurely closing the tag.
            val safeBundle = bundleJs.replace("</script", "<\\/script")
            // JSON-encode the token so a quote or a </script> inside it cannot break out of the script.
            val tokenJson = JSONArray().put(clientToken).toString()
            return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>html,body{margin:0;padding:0;height:100%;width:100%}#meld-banxa{height:100%;width:100%}</style>
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
                [
                  'ready','payment-start','payment-success','payment-failure','payment-cancel','card-error'
                ].forEach(function(name){
                  el.addEventListener('banxa:'+name, function(e){ post({type:name, detail:(e?e.detail:null)}); });
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
