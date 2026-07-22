package io.meld.sdk

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import org.json.JSONObject

/**
 * Uphold credit/debit card (Option 1 — the SDK absorbs Uphold's two-step card flow). Uphold requires
 * the card captured BEFORE a purchase quote can be created, so a single widget can't do it. This
 * adapter orchestrates both Uphold widgets so the integrator still mounts once:
 *
 *   1. Mount the capture (select-for-deposit) widget from the order's session.
 *   2. On card capture, POST the captured card id to the order's `authorizeSessionUrl` (bearer =
 *      `continuationToken`) via [MeldApiClient] to create the authorize session.
 *   3. Mount the returned authorize widget; its `ready/complete/cancel/error` map to Meld events
 *      (`complete` = UX hint, NOT settlement — settlement is confirmed server-side via webhook).
 *
 * Uphold's payment widget is **SDK-mounted, not URL-loadable**: its web SDK is ESM-only and, given a
 * session (`{url, token, flow}`), builds and mounts its own iframe against the widget host using the
 * token. So we can't just load a URL in a WebView — instead we run Uphold's web SDK (vendored as a
 * bundled asset, [BUNDLE_ASSET]) inside the reused [WebViewHost] via a bootstrap HTML that calls
 * `PaymentWidget(session).mountIframe(...)` and relays its events through `window.meldSendToNativeApp`.
 * The order carries the session as `serviceProviderWidgetUrl` (session url) + `sdkSessionToken` +
 * `sdkSessionFlow`. Distinguished from other IFRAME card providers by widget/API host.
 */
internal class UpholdCardAdapter : MeldAdapter {
    override val label = "Uphold card (CREDIT_DEBIT_CARD / IFRAME)"
    override val capabilities =
        MeldCapabilities(embeddable = true, surface = "embedded", requiresUserGesture = false)

    override fun matches(paymentMethodType: String?, renderMode: String?, widgetUrl: String?): Boolean =
        paymentMethodType == "CREDIT_DEBIT_CARD" && renderMode == "IFRAME" && isUpholdHost(widgetUrl)

    override fun mount(
        order: MeldOrder,
        host: ViewGroup,
        handlers: MeldEventHandlers,
    ): MeldProviderSession {
        val details = order.paymentMethodResponseDetails
        val sessionUrl = details?.serviceProviderWidgetUrl ?: throw MeldMountException.MissingWidgetUrl
        val sessionToken = details["sdkSessionToken"] as? String
            ?: throw MeldMountException.Unsupported(
                "Uphold card order is missing sdkSessionToken (needed to mount the Uphold widget SDK).",
            )
        val sessionFlow = details["sdkSessionFlow"] as? String
        val authorizeSessionUrl = details["authorizeSessionUrl"] as? String
            ?: throw MeldMountException.Unsupported(
                "Uphold card order is missing authorizeSessionUrl (needed for the capture→authorize step).",
            )
        val continuationToken = details["continuationToken"] as? String

        val bundle = loadBundle(host.context)
        warnIfEnvironmentMismatch(Uri.parse(sessionUrl).host)

        val session = UpholdTwoStepSession(
            host, handlers, order.id, bundle, authorizeSessionUrl, continuationToken, order.paymentMethodType,
        )
        session.mountCapture(sessionUrl, sessionToken, sessionFlow)
        return session
    }

    /** Orchestrates the capture widget → Meld authorize-session call → authorize widget. */
    private inner class UpholdTwoStepSession(
        private val host: ViewGroup,
        private val handlers: MeldEventHandlers,
        private val orderId: String?,
        private val bundle: String,
        private val authorizeSessionUrl: String,
        private val continuationToken: String?,
        paymentMethodType: String?,
    ) : MeldProviderSession {

        // Uphold widget `paymentMethods` config derived from the Meld order's paymentMethodType, so the
        // widget preselects the method (skipping its "Select a payment method" screen) when we can map it.
        private val paymentMethodsJs = upholdPaymentMethodsJs(paymentMethodType)

        private val main = Handler(Looper.getMainLooper())

        @Volatile
        private var current: MeldProviderSession? = null

        @Volatile
        private var advancing = false

        // Set on unmount so an in-flight authorize-session network call (below) can't mount a WebView into
        // a torn-down host afterwards — that would leak the WebView + the Activity context it holds and
        // could render after teardown.
        @Volatile
        private var unmounted = false

        // The flow spans two WebView hosts (capture → authorize), each of which would fire onReady on its
        // page load. Forward only the first so the integrator sees one Ready per Meld.mount.
        private val readyForwarded = java.util.concurrent.atomic.AtomicBoolean(false)
        private val onceHandlers = MeldEventHandlers(
            onReady = { id -> if (readyForwarded.compareAndSet(false, true)) handlers.onReady?.invoke(id) },
            onPaymentSubmitted = handlers.onPaymentSubmitted,
            onStatusChange = handlers.onStatusChange,
            onCancel = handlers.onCancel,
            onError = handlers.onError,
        )

        fun mountCapture(sessionUrl: String, token: String, flow: String?) {
            val capture = WebViewHost(
                url = widgetOrigin(),
                orderId = orderId,
                handlers = onceHandlers,
                allowedOrigins = allowedOrigins,
                htmlContent = bootstrapHtml(bundle, sessionUrl, token, flow, paymentMethodsJs = paymentMethodsJs),
            ) { message ->
                val cardId = extractCapturedCardId(message)
                val type = (message["type"] ?: message["event"]) as? String
                when {
                    cardId != null -> {
                        if (!advancing) {
                            advancing = true
                            advanceToAuthorize(cardId)
                        }
                        emptyList() // capture 'complete' is internal; don't surface it as settlement
                    }
                    // A capture 'complete' with no extractable card id is a dead end — the widget won't
                    // fire anything else, so surface an error instead of silently stalling.
                    type == "complete" ->
                        listOf(
                            MeldEvent.Error(
                                MeldError(
                                    orderId = orderId,
                                    code = "authorize_session_invalid",
                                    message = "Uphold capture completed without a card id",
                                    recoverable = false,
                                ),
                            ),
                        )
                    else -> interpretCapture(message)
                }
            }
            current = capture
            capture.mount(host)
        }

        private fun advanceToAuthorize(cardId: String) {
            Thread {
                try {
                    val authorizeOrder =
                        MeldApiClient.createAuthorizeSession(authorizeSessionUrl, continuationToken, cardId)
                    if (unmounted) return@Thread
                    main.post { if (!unmounted) mountAuthorize(authorizeOrder) }
                } catch (e: Exception) {
                    if (unmounted) return@Thread
                    Log.w(TAG, "Uphold authorize-session failed", e)
                    main.post {
                        if (unmounted) return@post
                        handlers.onError?.invoke(
                            MeldError(
                                orderId = orderId,
                                code = "authorize_session_failed",
                                message = e.message ?: "Failed to create Uphold authorize session",
                                recoverable = false,
                            ),
                        )
                    }
                }
            }.start()
        }

        private fun mountAuthorize(authorizeOrder: MeldOrder) {
            if (unmounted) return
            val details = authorizeOrder.paymentMethodResponseDetails
            val sessionUrl = details?.serviceProviderWidgetUrl
            val token = details?.get("sdkSessionToken") as? String
            if (sessionUrl == null || token == null) {
                handlers.onError?.invoke(
                    MeldError(orderId, "authorize_session_invalid", "authorize order missing session", null, false),
                )
                return
            }
            val flow = details["sdkSessionFlow"] as? String
            val data = (details["sdkSessionData"] as? Map<*, *>)?.let { JSONObject(it) }
            current?.unmount()
            val authorize = WebViewHost(
                url = widgetOrigin(),
                orderId = orderId,
                handlers = onceHandlers,
                allowedOrigins = allowedOrigins,
                htmlContent = bootstrapHtml(bundle, sessionUrl, token, flow, data, paymentMethodsJs),
            ) { message -> interpret(message, orderId) }
            current = authorize
            authorize.mount(host)
        }

        override fun unmount() {
            unmounted = true
            // Drop any queued mountAuthorize/onError post so an in-flight authorize-session call can't
            // resurrect a WebView (or fire events) after teardown.
            main.removeCallbacksAndMessages(null)
            current?.unmount()
            current = null
        }
    }

    private fun warnIfEnvironmentMismatch(host: String?) {
        if (host == null) return
        val orderEnv = hostsByEnvironment.entries
            .firstOrNull { it.value.contains(host) }?.key ?: return
        if (orderEnv != Meld.environment) {
            Log.w(
                TAG,
                "order environment is '${orderEnv.raw}' but Meld.configure set " +
                    "'${Meld.environment.raw}'. Configure the matching environment to silence this.",
            )
        }
    }

    private fun loadBundle(context: Context): String {
        cachedBundle?.let { return it }
        val bytes = try {
            context.assets.open(BUNDLE_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            throw MeldMountException.Unsupported("Uphold widget SDK bundle ($BUNDLE_ASSET) is missing from SDK assets.")
        }
        verifyBundleIntegrity(bytes)
        val js = String(bytes, Charsets.UTF_8)
        cachedBundle = js
        return js
    }

    // Pin the vendored Uphold widget bundle by content hash and refuse to execute anything else. The
    // bundle owns the widget's postMessage/bridge security surface, so a swapped or tampered asset must
    // fail closed rather than run inside the WebView with a native bridge attached.
    private fun verifyBundleIntegrity(bytes: ByteArray) {
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actual != EXPECTED_BUNDLE_SHA256) {
            throw MeldMountException.Unsupported(
                "Uphold widget SDK bundle failed its pinned integrity check " +
                    "(expected $EXPECTED_BUNDLE_SHA256, got $actual); refusing to execute.",
            )
        }
    }

    /** Widget-host origin for the current environment; the bootstrap page's base URL / origin. */
    private fun widgetOrigin(): String {
        val host = widgetHostsByEnvironment[Meld.environment]?.firstOrNull()
            ?: widgetHostsByEnvironment.getValue(MeldEnvironment.SANDBOX).first()
        return "https://$host"
    }

    companion object {
        private const val TAG = "MeldSDK"

        @VisibleForTesting
        internal const val BUNDLE_ASSET = "uphold-payment-widget.bundle.js"

        // SHA-256 of the pinned vendored bundle. Update deliberately (and re-review) when the bundle is
        // intentionally revved; a drift here fails the mount rather than silently running new widget code.
        @VisibleForTesting
        internal const val EXPECTED_BUNDLE_SHA256 =
            "a4664a8568533df6f55b1e8db303ec3fa6a7f7ce73aca8d08e4acaf8c5cc823e"

        @Volatile
        private var cachedBundle: String? = null

        // Map the Meld order's paymentMethodType to Uphold's widget `paymentMethods` config so the widget
        // preselects it and skips the "Select a payment method" screen. Card-family Meld types map to
        // Uphold's `card`; anything we can't confidently map falls back to the full picker (card + crypto).
        @VisibleForTesting
        internal fun upholdPaymentMethodsJs(paymentMethodType: String?): String =
            when (paymentMethodType?.uppercase()) {
                "CREDIT_DEBIT_CARD", "CREDIT_CARD", "DEBIT_CARD", "CARD" -> "[{type:'card'}]"
                else -> "[{type:'card'},{type:'crypto'}]"
            }

        // MARK: - Bootstrap that runs Uphold's web SDK inside the WebView

        /**
         * HTML that loads the vendored Uphold web SDK, mounts `PaymentWidget(session)`, and relays its
         * lifecycle events to native through `window.meldSendToNativeApp` in the normalized shape the
         * event mappers below expect ({@code {type, value|detail}}). The session (`{url, token, flow}`)
         * is what Uphold's SDK needs to build its own iframe against the widget host.
         */
        @VisibleForTesting
        internal fun bootstrapHtml(
            bundleJs: String,
            sessionUrl: String,
            token: String,
            flow: String?,
            data: JSONObject? = null,
            paymentMethodsJs: String = "[{type:'card'},{type:'crypto'}]",
        ): String {
            val session = JSONObject().put("url", sessionUrl).put("token", token)
            if (flow != null) session.put("flow", flow)
            // Uphold's authorize flow requires the quote id as session `data.quoteId` — the token alone
            // isn't enough (the PaymentWidget throws "Missing 'quoteId' parameter in 'data'"). The backend
            // supplies it on the authorize order as sdkSessionData; the capture step passes nothing.
            if (data != null) session.put("data", data)
            // Guard against a literal </script> inside the bundle prematurely closing the tag.
            val safeBundle = bundleJs.replace("</script", "<\\/script")
            return """
                <!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
                <style>html,body{margin:0;padding:0;height:100%;width:100%}#meld-uphold{height:100%;width:100%}</style>
                </head><body><div id="meld-uphold"></div>
                <script>$safeBundle</script>
                <script>
                (function(){
                  function post(m){ try{ if(window.meldSendToNativeApp){ window.meldSendToNativeApp({kind:'message',data:m}); } }catch(e){} }
                  try {
                    var W = window.MeldUpholdWidget && window.MeldUpholdWidget.PaymentWidget;
                    if(!W){ post({type:'error',detail:{error:{code:'sdk_unavailable',message:'Uphold widget SDK failed to load'}}}); return; }
                    var widget = new W($session, { paymentMethods:$paymentMethodsJs, theme:{appearance:'light'} });
                    widget.on('ready', function(){ post({type:'ready'}); });
                    widget.on('complete', function(e){ post({type:'complete', value:(e&&e.detail)?e.detail.value:null}); });
                    widget.on('cancel', function(){ post({type:'cancel'}); });
                    widget.on('error', function(e){ post({type:'error', detail:(e?e.detail:null)}); });
                    widget.mountIframe(document.getElementById('meld-uphold'));
                  } catch(err){ post({type:'error',detail:{error:{code:'mount_failed',message:String((err&&err.message)||err)}}}); }
                })();
                </script></body></html>
            """.trimIndent()
        }

        // MARK: - Authorize-widget message -> Meld events

        @VisibleForTesting
        internal fun interpret(providerMessage: Map<String, Any?>, orderId: String?): List<MeldEvent> {
            val type = (providerMessage["type"] ?: providerMessage["event"]) as? String ?: return emptyList()
            return when (type) {
                "ready" -> listOf(MeldEvent.Ready)
                "complete" -> listOf(MeldEvent.PaymentSubmitted) // UX hint; settlement via webhook
                "cancel" -> listOf(MeldEvent.Cancel)
                "error" -> listOf(MeldEvent.Error(errorFrom(providerMessage, orderId)))
                else -> emptyList()
            }
        }

        // Capture (select-for-deposit) step: surface ready so the widget shows, and terminal
        // cancel/error to the integrator; the 'complete' (card captured) is handled out-of-band.
        @VisibleForTesting
        internal fun interpretCapture(providerMessage: Map<String, Any?>): List<MeldEvent> {
            return when ((providerMessage["type"] ?: providerMessage["event"]) as? String) {
                "ready" -> listOf(MeldEvent.Ready)
                "cancel" -> listOf(MeldEvent.Cancel)
                "error" -> listOf(MeldEvent.Error(errorFrom(providerMessage, null)))
                else -> emptyList()
            }
        }

        // Card id from the capture widget's 'complete' selection. POC-confirmed shape: the SDK's
        // complete event carries detail.value = { via:'external-account', selection:{ id, label } }; the
        // bootstrap forwards detail.value as `value`, so the id is value.selection.id.
        @Suppress("UNCHECKED_CAST")
        @VisibleForTesting
        internal fun extractCapturedCardId(providerMessage: Map<String, Any?>): String? {
            val type = (providerMessage["type"] ?: providerMessage["event"]) as? String
            if (type != "complete") return null
            val value = (providerMessage["value"] as? Map<String, Any?>)
                ?: ((providerMessage["detail"] as? Map<String, Any?>)?.get("value") as? Map<String, Any?>)
                ?: return null
            val selection = value["selection"] as? Map<String, Any?> ?: return null
            return selection["id"] as? String
        }

        @Suppress("UNCHECKED_CAST")
        private fun errorFrom(providerMessage: Map<String, Any?>, orderId: String?): MeldError {
            val detail = providerMessage["detail"] as? Map<String, Any?>
            val error = detail?.get("error") as? Map<String, Any?>
            return MeldError(
                orderId = orderId,
                code = (error?.get("code") as? String) ?: "error",
                message = (error?.get("message") as? String) ?: "Uphold widget error",
                recoverable = false,
            )
        }

        // MARK: - Uphold hosts (both the API host that carries the session url and the widget host)

        private val apiHostsByEnvironment: Map<MeldEnvironment, Set<String>> = mapOf(
            MeldEnvironment.SANDBOX to setOf("api.enterprise.sandbox.uphold.com"),
            MeldEnvironment.PRODUCTION to setOf("api.enterprise.uphold.com"),
        )

        private val widgetHostsByEnvironment: Map<MeldEnvironment, Set<String>> = mapOf(
            MeldEnvironment.SANDBOX to setOf("payment-widget.enterprise.sandbox.uphold.com"),
            MeldEnvironment.PRODUCTION to setOf("payment-widget.enterprise.uphold.com"),
        )

        // Env → the host(s) that identify that env, for the config-mismatch warning (keyed off the
        // session url, which is the API host).
        private val hostsByEnvironment: Map<MeldEnvironment, Set<String>> =
            apiHostsByEnvironment.mapValues { (env, api) -> api + widgetHostsByEnvironment.getValue(env) }

        // Both the bootstrap page origin (widget host) and the Uphold iframe/API origins are trusted.
        internal val allowedOrigins: Set<String> =
            (apiHostsByEnvironment.values.flatten() + widgetHostsByEnvironment.values.flatten())
                .map { "https://$it" }
                .toSet()

        private val allHosts: Set<String> =
            (apiHostsByEnvironment.values.flatten() + widgetHostsByEnvironment.values.flatten()).toSet()

        @VisibleForTesting
        internal fun isUpholdHost(widgetUrl: String?): Boolean {
            if (widgetUrl.isNullOrEmpty()) return false
            return Uri.parse(widgetUrl).host in allHosts
        }
    }
}
