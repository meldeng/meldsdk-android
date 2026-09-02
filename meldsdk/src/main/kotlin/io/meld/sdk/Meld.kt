package io.meld.sdk

import android.util.Log
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Public surface of the SDK. The same shape as the web SDK (@meldcrypto/sdk) and the iOS SDK:
//   Meld.configure(environment)
//   Meld.capabilities(order)
//   Meld.mount(order, host, handlers)
//   handle.unmount()
// The order's paymentMethodType and renderMode select the provider widget to embed, so
// supporting a new provider does not change this API. Supported today: Mercuryo card.

enum class MeldEnvironment(val raw: String) {
    SANDBOX("sandbox"),
    PRODUCTION("production"),
}

/**
 * The HeadlessOrderResponse from `POST /crypto/order/headless`, passed verbatim. The fields the
 * SDK reads are exposed directly; the whole `paymentMethodResponseDetails` is also kept as [raw]
 * so provider-specific fields (a session token, etc.) stay available without modeling each one.
 */
class MeldOrder private constructor(
    val id: String?,
    val paymentMethodType: String?,
    /**
     * `payload.serviceProvider` from the headless order response — the provider that will process this
     * order (e.g. "BANXA", "MERCURYO"). Adapters are per-provider, so this is what the registry
     * dispatches on for providers that render from an SDK token and so carry no widget host to
     * identify them by.
     */
    val serviceProvider: String?,
    val paymentMethodResponseDetails: Details?,
) {
    class Details(
        val serviceProviderWidgetUrl: String?,
        val renderMode: String?,
        /** Every detail field as returned, including provider-specific ones not modeled above. */
        val raw: Map<String, Any?>,
    ) {
        /** Convenience access to a raw detail field (e.g. a provider session token). */
        operator fun get(key: String): Any? = raw[key]
    }

    companion object {
        /** Decode the order your backend returns (pass it through untouched). */
        @JvmStatic
        @Throws(MeldOrderException::class)
        fun fromJson(json: String): MeldOrder {
            val dict = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw MeldOrderException.Malformed
            }
            return fromMap(dict.toMap())
        }

        /** Decode an order already parsed into a map (e.g. from the React Native bridge). */
        @JvmStatic
        fun fromMap(dict: Map<String, Any?>): MeldOrder {
            @Suppress("UNCHECKED_CAST")
            val detailsMap = dict["paymentMethodResponseDetails"] as? Map<String, Any?>
            val details = detailsMap?.let {
                Details(
                    serviceProviderWidgetUrl = it["serviceProviderWidgetUrl"] as? String,
                    renderMode = it["renderMode"] as? String,
                    raw = it,
                )
            }
            // The provider is echoed under `payload` on the headless create response. It is required
            // on the request, so it is always present there — but read defensively, since the mid-flow
            // authorize-session response (Uphold) is a different shape that carries no payload.
            @Suppress("UNCHECKED_CAST")
            val payload = dict["payload"] as? Map<String, Any?>
            return MeldOrder(
                id = dict["id"] as? String,
                paymentMethodType = dict["paymentMethodType"] as? String,
                serviceProvider = payload?.get("serviceProvider") as? String,
                paymentMethodResponseDetails = details,
            )
        }
    }
}

sealed class MeldOrderException(message: String) : Exception(message) {
    /** The JSON was not a JSON object. */
    object Malformed : MeldOrderException("Order JSON is not a JSON object.")
}

/** Normalized order status, consistent across providers. */
enum class MeldStatus(val raw: String) {
    PENDING("pending"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
}

data class MeldStatusChange(
    val orderId: String?,
    val status: MeldStatus,
    val providerStatus: String?,
    val raw: Any?,
)

data class MeldError(
    val orderId: String?,
    val code: String,
    val message: String,
    /** Extra diagnostic detail when the SDK has it (e.g. a load-failure probe). May be null. */
    val detail: String? = null,
    val recoverable: Boolean,
)

/**
 * Lifecycle callbacks. Each callback receives the id of the order it relates to, so an app
 * handling several orders at once can tell them apart. Always invoked on the main thread.
 */
class MeldEventHandlers(
    val onReady: ((orderId: String?) -> Unit)? = null,
    val onPaymentSubmitted: ((orderId: String?) -> Unit)? = null,
    val onStatusChange: ((MeldStatusChange) -> Unit)? = null,
    val onCancel: ((orderId: String?) -> Unit)? = null,
    val onError: ((MeldError) -> Unit)? = null,
)

data class MeldCapabilities(
    val embeddable: Boolean,
    val surface: String,
    val requiresUserGesture: Boolean,
)

sealed class MeldMountException(message: String) : Exception(message) {
    /** No adapter handles the order. The detail lists whatever providers are supported. */
    class Unsupported(detail: String) : MeldMountException(detail)
    object MissingWidgetUrl :
        MeldMountException("Order has no paymentMethodResponseDetails.serviceProviderWidgetUrl to load.")
}

/**
 * Handle returned by [Meld.mount] — call [unmount] on teardown (navigation, dismissal).
 *
 * The handle STRONGLY owns the provider session: it is the mounted widget's lifecycle owner, so the
 * session must live exactly as long as the integrator keeps the handle. A weak reference would let a
 * session that isn't itself retained by the view tree be garbage-collected right after [Meld.mount]
 * returns — fatal for multi-step providers (e.g. Uphold), whose orchestrating session object owns the
 * WebView(s) rather than being one. No leak: retain this handle until you [unmount]; calling [unmount]
 * after teardown is a no-op.
 */
class MeldWidgetHandle internal constructor(
    val mode: String,
    private val session: MeldProviderSession,
) {
    fun unmount() {
        session.unmount()
    }
}

object Meld {
    private const val TAG = "MeldSDK"

    @Volatile
    var environment: MeldEnvironment = MeldEnvironment.SANDBOX
        private set

    // Adapter registry — the only place provider knowledge lives. Dispatch is on
    // (paymentMethodType, renderMode); first match wins. Supporting a new provider is a new
    // entry here, never a change to the public API or the generic widget host.
    internal val adapters: List<MeldAdapter> = listOf(
        // Provider-specific IFRAME adapters first (they host-gate on widgetUrl); the generic
        // Mercuryo IFRAME-card adapter is the catch-all and must stay last.
        UpholdCardAdapter(),
        // Banxa carries no widget URL at all (it renders from an SDK token), so it must come before
        // the Mercuryo catch-all — which would otherwise claim the order and then fail on the missing
        // serviceProviderWidgetUrl.
        BanxaCardAdapter(),
        MercuryoCardAdapter(),
    )

    @JvmStatic
    fun configure(environment: MeldEnvironment) {
        if (this.environment != environment) {
            Log.i(TAG, "environment set to '${environment.raw}'.")
        }
        this.environment = environment
    }

    @JvmStatic
    fun capabilities(order: MeldOrder): MeldCapabilities =
        adapterFor(order)?.capabilities
            ?: MeldCapabilities(embeddable = false, surface = "unsupported", requiresUserGesture = false)

    /**
     * Mount the provider widget into a host [ViewGroup] you own. Returns a handle; call
     * `handle.unmount()` to tear down.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(MeldMountException::class)
    fun mount(
        order: MeldOrder,
        host: ViewGroup,
        handlers: MeldEventHandlers = MeldEventHandlers(),
    ): MeldWidgetHandle {
        val adapter = adapterFor(order) ?: run {
            val type = order.paymentMethodType ?: "null"
            val mode = order.paymentMethodResponseDetails?.renderMode ?: "null"
            val supported = adapters.joinToString(", ") { it.label }
            throw MeldMountException.Unsupported(
                "No embeddable adapter for paymentMethodType=$type renderMode=$mode. " +
                    "This SDK supports: $supported. " +
                    "Guard with Meld.capabilities(order).embeddable before mount.",
            )
        }
        // The adapter owns how its widget is rendered (URL in a WebView, provider SDK, …).
        val session = adapter.mount(order, host, handlers)
        return MeldWidgetHandle(adapter.capabilities.surface, session)
    }

    /** First registered adapter that handles the order, or null if none do. */
    internal fun adapterFor(order: MeldOrder): MeldAdapter? {
        return adapters.firstOrNull { it.matches(order) }
    }
}

// MARK: - org.json -> Kotlin collections

internal fun JSONObject.toMap(): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>(length())
    for (key in keys()) {
        map[key] = unwrap(get(key))
    }
    return map
}

internal fun JSONArray.toList(): List<Any?> {
    val list = ArrayList<Any?>(length())
    for (i in 0 until length()) {
        list.add(unwrap(get(i)))
    }
    return list
}

private fun unwrap(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> value.toList()
    else -> value
}
