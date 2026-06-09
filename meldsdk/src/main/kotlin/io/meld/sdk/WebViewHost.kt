package io.meld.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject

// Generic WebView host: loads a URL in a WebView, forwards the page's window messages to native,
// maps each through the supplied `interpret` lambda, and dispatches the resulting events to the
// Meld handlers. It carries no provider-specific knowledge — an adapter supplies the URL, the
// allowed message origins, and the message mapping. Reusable by any URL-rendered provider widget.
internal class WebViewHost(
    private val url: String,
    private val orderId: String?,
    private val handlers: MeldEventHandlers,
    allowedOrigins: Set<String> = emptySet(),
    private val interpret: (Map<String, Any?>) -> List<MeldEvent>,
) : MeldProviderSession {

    // Origins ("https://host") whose window.postMessage events the bridge trusts. The widget's own
    // origin is always included; an adapter may add the provider's other origins. An empty set
    // means "trust any origin" — only as a last resort, never for an embedded provider widget.
    private val allowedOrigins: Set<String> = buildSet {
        addAll(allowedOrigins)
        originOf(url)?.let { add(it) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var didFireReady = false
    private var loadFailed = false

    @SuppressLint("SetJavaScriptEnabled")
    fun mount(host: ViewGroup) {
        // A WebViewHost owns exactly one WebView. Re-mounting (e.g. a second Meld.mount into a
        // reused host) tears the previous one down first so the old bridge can't leak or stack a
        // second WebView on top.
        if (webView != null) unmount()
        didFireReady = false
        loadFailed = false

        val appContext = host.context.applicationContext
        val web = WebView(host.context)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // let the widget use the camera (KYC)
        }
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Page loaded; a provider's own ready event (if any) also fires this — whichever
                // first. Don't report ready if the main-frame load already failed (e.g. an HTTP
                // error page rendered).
                if (!loadFailed) fireReadyOnce()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // Fallback bridge injection for WebViews that don't support document-start scripts.
                if (!documentStartSupported) {
                    view?.evaluateJavascript(bridgeScript(), null)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Only surface main-frame failures; subresource errors are the page's concern.
                if (request?.isForMainFrame == true) {
                    loadFailed = true
                    emitError(
                        code = "PROVIDER_LOAD_FAILED",
                        message = error?.description?.toString() ?: "load failed",
                        detail = error?.let { "errorCode #${it.errorCode}" },
                        recoverable = true,
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                // A signed widget URL that 4xx/5xx (expired/invalid signature is the common case)
                // still "loads" an error body and would otherwise fire onReady as success. Treat a
                // main-frame HTTP error as a load failure instead.
                if (request?.isForMainFrame == true) {
                    loadFailed = true
                    val status = errorResponse?.statusCode ?: 0
                    emitError(
                        code = "PROVIDER_LOAD_FAILED",
                        message = "Widget URL returned HTTP $status",
                        detail = "httpStatus #$status",
                        recoverable = status >= 500, // 5xx may be transient; 4xx needs a new order
                    )
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // KYC: the widget asks for the camera for document/selfie capture. A WebChromeClient
                // grant only authorizes the web content — the app process must already hold the
                // CAMERA runtime permission, or getUserMedia fails anyway. Only grant if it does.
                main.post {
                    val wantsCamera = request.resources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                    val hasCameraPermission = appContext.checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (wantsCamera && hasCameraPermission) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        if (wantsCamera && !hasCameraPermission) {
                            Log.w(TAG, "widget requested the camera but the app lacks the CAMERA " +
                                "runtime permission; denying. Request it before mounting for KYC.")
                        }
                        request.deny()
                    }
                }
            }
        }

        web.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        // Inject the bridge at document start so it's listening before the widget posts anything.
        // Injected into all frames ("*"); the JS-level origin filter is what enforces trust.
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(web, bridgeScript(), setOf("*"))
        }

        host.addView(web)
        web.loadUrl(url)
        webView = web
    }

    override fun unmount() {
        // WebView methods must run on the thread that created it (main). Integrators may call
        // handle.unmount() from anywhere, so hop to main if needed.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { unmount() }
            return
        }
        val web = webView ?: return
        web.removeJavascriptInterface(BRIDGE_NAME)
        web.stopLoading()
        web.webViewClient = WebViewClient()
        web.webChromeClient = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        webView = null
    }

    private fun fireReadyOnce() {
        if (didFireReady) return
        didFireReady = true
        handlers.onReady?.invoke(orderId)
    }

    // MARK: - Bridge (window messages -> interpret -> Meld events)

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            // @JavascriptInterface methods arrive on a WebView background thread; hop to main so
            // all integrator callbacks are main-thread guaranteed.
            main.post { handleBridgeMessage(json) }
        }
    }

    private fun handleBridgeMessage(json: String) {
        // Each message arrives wrapped as { kind: "message", data: <provider event> }.
        val data = try {
            JSONObject(json).optJSONObject("data")
        } catch (e: JSONException) {
            null
        }
        if (data == null) {
            // Silently dropping makes provider-protocol drift invisible; surface it in debug.
            Log.d(TAG, "dropped malformed bridge payload: $json")
            return
        }
        for (event in interpret(data.toMap())) {
            dispatch(event)
        }
    }

    private fun dispatch(event: MeldEvent) {
        when (event) {
            is MeldEvent.Ready -> fireReadyOnce()
            is MeldEvent.PaymentSubmitted -> handlers.onPaymentSubmitted?.invoke(orderId)
            is MeldEvent.StatusChange -> handlers.onStatusChange?.invoke(event.change)
            is MeldEvent.Cancel -> handlers.onCancel?.invoke(orderId)
            is MeldEvent.Error -> handlers.onError?.invoke(event.error)
        }
    }

    private fun emitError(code: String, message: String, detail: String? = null, recoverable: Boolean) {
        handlers.onError?.invoke(MeldError(orderId, code, message, detail, recoverable))
    }

    // MARK: - Injected bridge

    /**
     * Runs at document start in the widget page and forwards the widget's window messages to the
     * native handler. Only messages from `allowedOrigins` are forwarded, so a malicious or
     * compromised subframe can't post fake lifecycle events. An empty allowlist forwards any
     * origin (last-resort fallback only). The payload is JSON-stringified because the JavaScript
     * interface only marshals strings.
     */
    private fun bridgeScript(): String {
        val originsJson = org.json.JSONArray(allowedOrigins.toList()).toString()
        return """
        (function () {
          var allowedOrigins = $originsJson;
          function send(message) {
            try { window.$BRIDGE_NAME.postMessage(JSON.stringify(message)); }
            catch (e) { if (window.console) console.warn('[MeldSDK] bridge post failed', e); }
          }
          // The widget calling this directly is trusted (same realm as our injected script).
          window.meldSendToNativeApp = send;
          window.addEventListener('message', function (event) {
            if (allowedOrigins.length && allowedOrigins.indexOf(event.origin) === -1) return;
            send({ kind: 'message', data: event.data });
          }, false);
        })();
        """.trimIndent()
    }

    companion object {
        private const val TAG = "MeldSDK"
        private const val BRIDGE_NAME = "meld"

        private val documentStartSupported: Boolean
            get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
}

/** Scheme + host of a URL as a postMessage origin string ("https://widget.mercuryo.io"). */
internal fun originOf(url: String): String? {
    val uri = Uri.parse(url)
    val scheme = uri.scheme ?: return null
    val host = uri.host ?: return null
    return "$scheme://$host"
}
