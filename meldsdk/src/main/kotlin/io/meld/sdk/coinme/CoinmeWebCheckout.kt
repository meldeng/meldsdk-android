package io.meld.sdk.coinme

import android.content.Context
import android.view.ViewGroup
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldError
import io.meld.sdk.MeldErrorCode
import io.meld.sdk.MeldEvent
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldMountException
import io.meld.sdk.MeldProviderSession
import io.meld.sdk.WebViewHost
import io.meld.sdk.actions.PaymentActionClient
import io.meld.sdk.actions.PaymentActionDescriptor
import io.meld.sdk.actions.PaymentActionResponse
import io.meld.sdk.actions.isOne
import io.meld.sdk.toMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Runs the Meld web SDK's Coinme flow in the shared [WebViewHost], as Banxa's web checkout runs: the
 * saved-card picker, Coinme's Vault fields and Risk session, the duplicate-card fallback and the wait
 * for card verification all live in the vendored bundle. The page sends each payment action over the
 * bridge; it goes from here to the order's own endpoint with the order's bearer, and the raw response
 * goes back for the web SDK to decode.
 */
internal class CoinmeWebCheckout(
    private val coinme: CoinmeOrder,
    private val order: Map<String, Any?>,
    private val environment: MeldEnvironment,
    private val handlers: MeldEventHandlers,
    private val actions: PaymentActionClient = PaymentActionClient(coinme.actions),
) {
    private var page: WebViewHost? = null

    fun present(host: ViewGroup): MeldProviderSession {
        val session = WebViewHost(
            url = PAGE_ORIGIN,
            orderId = coinme.id,
            handlers = handlers,
            htmlContent = bootstrapHtml(loadBundle(host.context), JSONObject(order).toString(), environment.raw),
            // The bootstrap is local HTML and loads at once; the flow fires its own ready once the device
            // is tagged and the cards or the form are showing.
            firesReadyOnNavigation = false,
            // Pinned so an action's response is only ever evaluated in this page.
            mainFrameHosts = setOf(PAGE_HOST),
        ) { message -> interpret(message) }
        page = session
        session.mount(host)
        return object : MeldProviderSession {
            override fun unmount() {
                page = null
                actions.close()
                session.unmount()
            }
        }
    }

    private fun interpret(message: Map<String, Any?>): List<MeldEvent> {
        if (message["type"] != "action") return events(message, coinme.id)
        val id = message["id"] as? String ?: return emptyList()
        val request = actionRequest(message)
        if (request == null) {
            reply(id, null)
        } else {
            actions.send(request.operation, request.fields, request.idempotencyKey) { reply(id, it) }
        }
        return emptyList()
    }

    private fun reply(id: String, response: PaymentActionResponse?) {
        page?.evaluateJavascript(resultScript(id, response)) {}
    }

    internal class ActionRequest(val operation: String, val fields: Map<String, Any?>, val idempotencyKey: UUID?)

    internal companion object {
        internal const val BUNDLE_ASSET = "meld-web-sdk.bundle.js"

        // SHA-256 of the vendored Meld web SDK bundle (meldsdk `node sdk/build.js`, with Coinme's Vault
        // 1.2.0 and Risk 1.1.1 web SDKs). Update deliberately when the bundle is revved; drift fails the
        // mount rather than silently running other checkout code.
        internal const val EXPECTED_BUNDLE_SHA256 =
            "a56b784d20cc7540c486e7a98013d64cf26d91ff3e4aa7d5e2f74a7161223db8"

        /**
         * The origin the bootstrap page claims; nothing is served or fetched from it. Ours, not Coinme's
         * or VGS's, so Coinme's secure card fields stay cross-origin to the page and its bridge script.
         */
        internal const val PAGE_HOST = "coinme-checkout.sdk.meld.io"
        internal const val PAGE_ORIGIN = "https://$PAGE_HOST"

        @Volatile
        private var cachedBundle: String? = null

        private fun loadBundle(context: Context): String {
            cachedBundle?.let { return it }
            val bytes = try {
                context.assets.open(BUNDLE_ASSET).use { it.readBytes() }
            } catch (e: Exception) {
                throw MeldMountException.Unsupported("Meld web SDK bundle ($BUNDLE_ASSET) is missing from SDK assets.")
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (actual != EXPECTED_BUNDLE_SHA256) {
                throw MeldMountException.Unsupported(
                    "Meld web SDK bundle failed its pinned integrity check " +
                        "(expected $EXPECTED_BUNDLE_SHA256, got $actual); refusing to execute.",
                )
            }
            return String(bytes, Charsets.UTF_8).also { cachedBundle = it }
        }

        /**
         * HTML that mounts [orderJson] with the web SDK, sends its payment actions to native as `action`
         * messages and relays its callbacks through `window.meldSendToNativeApp`. Native answers each
         * action through `window.__meldActionResult(id, status, body)`, a null status for none.
         */
        internal fun bootstrapHtml(bundleJs: String, orderJson: String, environment: String): String {
            // Guard against a literal </script> inside the bundle, or any markup in the order, ending the script early.
            val safeBundle = bundleJs.replace("</script", "<\\/script")
            val safeOrder = orderJson.replace("<", "\\u003c")
            val environmentJson = JSONArray().put(environment).toString()
            return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>html,body{margin:0;padding:0;background:#fff;
            font:15px/1.4 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}
            /* The WebView is edge-to-edge inside the host view, so the page owns its own gutter. */
            #meld-coinme{box-sizing:border-box;width:100%;padding:16px}</style>
            </head><body><div id="meld-coinme"></div>
            <script>$safeBundle</script>
            <script>
            (function(){
              function post(m){ try{ if(window.meldSendToNativeApp){ window.meldSendToNativeApp({kind:'message',data:m}); } }catch(e){} }
              var pending = {}, next = 0;
              window.__meldActionResult = function(id, status, body){
                var call = pending[id];
                if(!call) return;
                delete pending[id];
                if(typeof status === 'number'){ call.resolve({status:status, body:body}); } else { call.reject(new Error('transport')); }
              };
              try {
                var M = window.Meld;
                if(!M || typeof M.mount !== 'function' || typeof M.__setActionTransport !== 'function'){
                  post({type:'error',code:'PROVIDER_LOAD_FAILED',message:'The Meld web SDK failed to load',recoverable:false});
                  return;
                }
                M.configure({environment:$environmentJson[0]});
                M.__setActionTransport(function(request){
                  return new Promise(function(resolve, reject){
                    var id = String(++next);
                    pending[id] = {resolve:resolve, reject:reject};
                    if(request.signal){
                      request.signal.addEventListener('abort', function(){ if(pending[id]){ delete pending[id]; reject(new Error('aborted')); } });
                    }
                    post({type:'action', id:id, headers:request.headers, body:request.body});
                  });
                });
                M.mount($safeOrder, document.getElementById('meld-coinme'), {
                  onReady: function(){ post({type:'ready'}); },
                  onPaymentSubmitted: function(){ post({type:'submitted'}); },
                  onCancel: function(){ post({type:'cancel'}); },
                  onError: function(e){
                    post({type:'error', code:e&&e.code, message:e&&e.message, detail:e&&e.detail, recoverable:!!(e&&e.recoverable)});
                  }
                });
              } catch(err){
                post({type:'error',code:'PRESENTATION_FAILED',message:String((err&&err.message)||err),recoverable:false});
              }
            })();
            </script></body></html>
            """.trimIndent()
        }

        internal fun events(message: Map<String, Any?>, orderId: String?): List<MeldEvent> = when (message["type"]) {
            "ready" -> listOf(MeldEvent.Ready)
            // UX hint only: settlement is confirmed server-side from Coinme's webhook.
            "submitted" -> listOf(MeldEvent.PaymentSubmitted)
            "cancel" -> listOf(MeldEvent.Cancel)
            "error" -> listOf(
                MeldEvent.Error(
                    MeldError(
                        orderId,
                        message["code"] as? String ?: MeldErrorCode.PRESENTATION_FAILED.raw,
                        message["message"] as? String ?: "Coinme checkout error",
                        message["detail"] as? String,
                        message["recoverable"] == true,
                    ),
                ),
            )
            else -> emptyList()
        }

        /** The operation, fields and idempotency key of a page's action, or null for a malformed one. */
        internal fun actionRequest(message: Map<String, Any?>): ActionRequest? {
            val body = message["body"] as? String ?: return null
            if (body.length > PaymentActionDescriptor.BODY_LIMIT) return null
            val json = try {
                JSONObject(body).toMap()
            } catch (_: JSONException) {
                return null
            }
            val operation = json["operation"] as? String ?: return null
            if (!isOne(json["version"])) return null
            val idempotencyKey = when (val key = (message["headers"] as? Map<*, *>)?.get("X-Idempotency-Key")) {
                null -> null
                is String -> try {
                    UUID.fromString(key).takeIf { it.toString() == key }
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
                else -> return null
            }
            return ActionRequest(operation, json - "version" - "operation", idempotencyKey)
        }

        internal fun resultScript(id: String, response: PaymentActionResponse?): String {
            val status = response?.status?.toString() ?: "null"
            val body = response?.body?.let(JSONObject::quote) ?: "null"
            return "window.__meldActionResult(${JSONObject.quote(id)}, $status, $body);"
        }
    }
}
