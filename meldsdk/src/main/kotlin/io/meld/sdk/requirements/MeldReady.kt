package io.meld.sdk.requirements

/** How [io.meld.sdk.Meld.ensureReady] ended. The same names on the web, iOS and React Native. */
enum class MeldReadyStatus {
    /** Create the order. */
    READY,

    /** Meld could not tell; create the order anyway, since create is authoritative. */
    UNDETERMINED,

    /** Waiting on a review. Wait for `CUSTOMER_KYC_STATUS_CHANGE`, or call `ensureReady` again later. */
    PENDING,

    /** A provider refused. Offer another provider. */
    BLOCKED,

    /** The customer has something to do that this run could not: show [MeldReadyResult.pending], then run again. */
    ACTION_REQUIRED,
    CANCELLED,

    /**
     * The session was refused with 401 and no [MeldReadyHandlers.renewSession] gave one that works: it expired,
     * or it was minted in another environment than [io.meld.sdk.Meld.configure]'s, which the SDK cannot detect.
     */
    EXPIRED,
    FAILED,
}

data class MeldReadyResult(
    val status: MeldReadyStatus,
    /** Each session provider as last read, in session order. Empty if nothing was read. */
    val providers: List<MeldReadyProvider>,
    /**
     * What is left to do, only for [MeldReadyStatus.ACTION_REQUIRED]. Empty when no step can run here and none
     * is waited on, such as fields only the provider can supply: [providers]' `open` steps name it.
     */
    val pending: List<MeldReadyStep>? = null,
    /**
     * The steps a provider declares to run inside `Meld.mount(order)` after create, today only Stripe's identity
     * check, so [MeldReadyStatus.READY] can still be followed by them. This SDK does not present Stripe orders yet.
     * Other providers' own pages, such as Mercuryo's widget or Coinbase's payment page, are not listed: they open
     * when the order is mounted.
     */
    val continuation: List<MeldReadyContinuation> = emptyList(),
    /** Why the run failed, only for [MeldReadyStatus.FAILED]. */
    val error: MeldReadyError? = null,
)

data class MeldReadyProvider(
    val serviceProvider: String,
    /** `READY`, `ACTION_REQUIRED`, `WAITING`, `BLOCKED`, `UNDETERMINED` or a newer value. */
    val status: String,
    val eligible: Boolean?,
    /** The provider's pre-order steps that are not satisfied. */
    val open: List<MeldReadyOpenStep>,
)

data class MeldReadyOpenStep(
    val type: String,
    val code: String,
    val status: String,
    val reason: String? = null,
    /** The provider's codes for a share it refused, such as `PHONE_VERIFICATION_REQUIRED`; an open set. Null when Meld sends none. */
    val errorCodes: List<String>? = null,
)

data class MeldReadyContinuation(val serviceProvider: String, val code: String, val nextStep: String? = null)

enum class MeldReadyErrorKind(val raw: String) {
    HTTP("http"),
    TRANSPORT("transport"),
    PROTOCOL("protocol"),
}

data class MeldReadyError(val kind: MeldReadyErrorKind, val status: Int? = null, val code: String? = null)

/** One step the session offers. Its [url] is a capability link, so it never prints. */
data class MeldReadyStep(
    val stepId: String,
    /** `SUBMIT`, `COLLECT`, `AGREE`, `OPEN_URL`, `VERIFY_CONTACT` or a newer kind. */
    val kind: String,
    val type: String,
    val code: String,
    val serviceProviders: List<String>,
    val level: String? = null,
    val url: String? = null,
    val expiresAt: String? = null,
    val fields: List<MeldCollectField> = emptyList(),
    val reason: String? = null,
) {
    override fun toString(): String = "[MeldReadyStep REDACTED]"
}

data class MeldCollectField(
    val key: String,
    val label: String? = null,
    val fieldType: String? = null,
    val jsonSchema: String? = null,
    val allowedValues: List<String>? = null,
)

/** A field Meld refused and why: `REQUIRED`, `INVALID`, `UNSUPPORTED` or a newer reason. Never its value. */
data class MeldFieldError(val key: String, val reason: String)

/** Ask the customer for [fields]. [fieldErrors] names what was wrong with the last answer. */
data class MeldCollectRequest(
    val stepId: String,
    val type: String,
    val code: String,
    val serviceProviders: List<String>,
    val fields: List<MeldCollectField>,
    val reason: String? = null,
    val fieldErrors: List<MeldFieldError>? = null,
)

/** Ask the customer to accept [serviceProvider]'s terms, at [url] when it is known. */
data class MeldAgreementRequest(val stepId: String, val serviceProvider: String, val code: String, val url: String? = null)

/** Present a hosted page. Its [url] is a capability link, so it never prints. */
data class MeldPresentUrlRequest(val stepId: String, val url: String, val expiresAt: String? = null) {
    override fun toString(): String = "[MeldPresentUrlRequest REDACTED]"
}

enum class MeldReadyState { READING, RUNNING, AWAITING_INPUT, PRESENTING, WAITING }

/** What the run is doing. [snapshot] is the last READ as decoded, or null before the first one. */
class MeldReadyProgress internal constructor(val state: MeldReadyState, val snapshot: Map<String, Any?>?) {
    override fun toString(): String = "MeldReadyProgress(state=$state)"
}

/**
 * The integrator's part of a run. Each is called on the main thread, never after the result; answer through
 * its callback once, from any thread. A handler left out makes the steps that need it [MeldReadyStatus.ACTION_REQUIRED].
 * - [collect]: answer the field values, or null when you handled the step yourself.
 * - [confirmAgreement]: true once the customer accepted. The SDK never accepts on its own.
 * - [presentUrl]: present the page yourself and call back when it closed, instead of the browser.
 * - [renewSession]: mint a new session when this one expired, or answer null. Asked at most once between two
 *   answers and 3 times per run.
 */
data class MeldReadyHandlers(
    val onProgress: ((MeldReadyProgress) -> Unit)? = null,
    val collect: ((MeldCollectRequest, (Map<String, String>?) -> Unit) -> Unit)? = null,
    val confirmAgreement: ((MeldAgreementRequest, (Boolean) -> Unit) -> Unit)? = null,
    val presentUrl: ((MeldPresentUrlRequest, () -> Unit) -> Unit)? = null,
    val renewSession: (((MeldRequirementsSession?) -> Unit) -> Unit)? = null,
)

data class MeldReadyOptions(
    /** How long to keep polling while only reviews are left, before [MeldReadyStatus.PENDING]. */
    val waitTimeoutMs: Long = 600_000,
)

/**
 * A running [io.meld.sdk.Meld.ensureReady]. Its result arrives once, on the main thread. Once it has, the handle
 * holds neither the handlers nor the activity, so it can be kept.
 */
interface MeldReadyHandle {
    /** Read again now instead of at the next poll, such as when your backend relays a KYC webhook. */
    fun refresh()

    /** Stop the run and the request in flight. The result is [MeldReadyStatus.CANCELLED]. */
    fun cancel()
}
