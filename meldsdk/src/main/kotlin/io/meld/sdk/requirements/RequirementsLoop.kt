package io.meld.sdk.requirements

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.actions.PaymentActionFailureCode
import io.meld.sdk.hosted.Cancellable
import io.meld.sdk.hosted.MainScheduler
import io.meld.sdk.hosted.Scheduler
import io.meld.sdk.requirements.RequirementsSessionDescriptor.Companion.READ
import io.meld.sdk.requirements.RequirementsSessionDescriptor.Companion.RUN_STEP
import io.meld.sdk.wallet.parseRfc3339
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a requirements session to an answer: READ, run the first step it can, READ again. The server plans
 * every step, so this only executes what `next` offers and never decides readiness itself. Everything runs
 * on [main]; HTTP runs on the session's actions thread.
 */
internal class RequirementsLoop(
    private val environment: MeldEnvironment,
    private var handlers: MeldReadyHandlers,
    private val options: MeldReadyOptions,
    private var presenter: HostedPagePresenter?,
    private var onResult: ((MeldReadyResult) -> Unit)?,
    private val main: Scheduler = MainScheduler,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newKey: () -> UUID = UUID::randomUUID,
    private val connect: (RequirementsSessionDescriptor) -> SessionTransport = { SessionTransport(it) },
) : MeldReadyHandle {
    private var transport: SessionTransport? = null
    private var snapshot: RequirementsSnapshot? = null
    private var resolved = false
    private var waitStart: Long? = null
    private val attempted = HashMap<String, String>()
    private val settleUntil = HashMap<String, Long>()
    private val outcomeUnknown = HashMap<String, Int>()
    private var renewedSinceAnswer = false
    private var renewals = 0
    private var timer: Cancellable? = null
    private var polling = false
    private var presentation: Cancellable? = null
    private var unbind: (() -> Unit)? = null

    /** Ends the run when [lifecycle] is destroyed. Call on the main thread, before [start]. */
    fun bind(lifecycle: Lifecycle) {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return cancel()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) cancel() }
        lifecycle.addObserver(observer)
        unbind = { lifecycle.removeObserver(observer) }
    }

    fun start(session: MeldRequirementsSession) = onMain {
        if (!resolved && use(session)) read()
    }

    override fun refresh() = onMain {
        if (resolved || !polling) return@onMain
        cancelTimer()
        read()
    }

    override fun cancel() = onMain { resolve(MeldReadyStatus.CANCELLED) }

    // MARK: - The loop

    private fun read(afterRenewal: (() -> Unit)? = null, then: (RequirementsSnapshot) -> Unit = ::onSnapshot) {
        if (!progress(MeldReadyState.READING)) return
        send(READ, emptyMap(), null) { result ->
            val answer = result.getOrElse { return@send fail(it, afterRenewal) }
            then(RequirementsSnapshot.parse(answer) ?: return@send failWith(MeldReadyErrorKind.PROTOCOL))
        }
    }

    private fun onSnapshot(read: RequirementsSnapshot) {
        snapshot = read
        when (read.status) {
            "READY" -> return resolve(MeldReadyStatus.READY)
            "UNDETERMINED" -> return resolve(MeldReadyStatus.UNDETERMINED)
            "BLOCKED" -> return resolve(MeldReadyStatus.BLOCKED)
        }
        val step = read.next.firstOrNull { it.kind in KINDS && canRun(it) }
        if (step == null) {
            if (read.next.isNotEmpty()) return resolve(MeldReadyStatus.ACTION_REQUIRED, read.next)
            if (read.status == "ACTION_REQUIRED" && read.waitingOn.isEmpty()) return resolve(MeldReadyStatus.ACTION_REQUIRED, emptyList())
            val started = waitStart ?: main.now().also { waitStart = it }
            if (main.now() - started > options.waitTimeoutMs) return resolve(MeldReadyStatus.PENDING)
            return poll(read.pollAfterMs)
        }
        waitStart = null
        if (attempted[step.stepId] == read.fingerprint) {
            if (main.now() < (settleUntil[step.stepId] ?: 0)) return poll(read.pollAfterMs)
            return resolve(MeldReadyStatus.ACTION_REQUIRED, listOf(step))
        }
        attempted[step.stepId] = read.fingerprint
        run(step)
    }

    private fun canRun(step: MeldReadyStep): Boolean = when (step.kind) {
        SUBMIT -> true
        COLLECT, VERIFY_CONTACT -> handlers.collect != null
        AGREE -> handlers.confirmAgreement != null
        OPEN_URL -> handlers.presentUrl != null || presenter?.available == true
        else -> false
    }

    private fun run(step: MeldReadyStep) {
        when (step.kind) {
            SUBMIT, VERIFY_CONTACT -> runStep(step, emptyMap()) { outcome(step, it) }
            COLLECT -> collect(step, step.fields, continuation = null, fieldErrors = null, asked = 0)
            AGREE -> agree(step)
            OPEN_URL -> openUrl(step)
        }
    }

    /** The step ran. Its effect is read next; the same read again waits out [SETTLE_WINDOW_MS] before giving up. */
    private fun ran(step: MeldReadyStep) {
        settleUntil[step.stepId] = main.now() + SETTLE_WINDOW_MS
        read()
    }

    private fun outcome(step: MeldReadyStep, outcome: RequirementsOutcome) {
        when (outcome.outcome) {
            RequirementsOutcome.OPEN_URL -> {
                val url = outcome.url ?: return ran(step)
                present(step, url, outcome.expiresAt) { ran(step) }
            }
            RequirementsOutcome.COLLECT -> collect(step, outcome.fields, outcome.continuation, fieldErrors = null, asked = 0)
            else -> ran(step)
        }
    }

    private fun collect(
        step: MeldReadyStep,
        fields: List<MeldCollectField>,
        continuation: String?,
        fieldErrors: List<MeldFieldError>?,
        asked: Int,
    ) {
        val ask = handlers.collect ?: return resolve(MeldReadyStatus.ACTION_REQUIRED, listOf(step))
        if (!progress(MeldReadyState.AWAITING_INPUT)) return
        val request = MeldCollectRequest(step.stepId, step.type, step.code, step.serviceProviders, fields, step.reason, fieldErrors)
        ask(request, once { values: Map<String, String>? ->
            if (values == null) return@once ran(step)
            val body = mapOf("fields" to values) + (continuation?.let { mapOf("continuation" to it) } ?: emptyMap())
            runStep(step, body, onFieldErrors = { errors ->
                if (asked >= MAX_RECOLLECTS) {
                    resolve(MeldReadyStatus.ACTION_REQUIRED, listOf(step))
                } else {
                    collect(step, fields, continuation, errors, asked + 1)
                }
            }) { outcome(step, it) }
        })
    }

    private fun agree(step: MeldReadyStep) {
        val confirm = handlers.confirmAgreement ?: return resolve(MeldReadyStatus.ACTION_REQUIRED, listOf(step))
        if (!progress(MeldReadyState.AWAITING_INPUT)) return
        confirm(MeldAgreementRequest(step.stepId, step.serviceProviders.first(), step.code, step.url), once { accepted: Boolean ->
            if (!accepted) return@once resolve(MeldReadyStatus.ACTION_REQUIRED, listOf(step))
            runStep(step, mapOf("accepted" to true)) { outcome(step, it) }
        })
    }

    /** A link about to expire is refreshed before it shows, and the step is refreshed once after it closes. */
    private fun openUrl(step: MeldReadyStep) {
        val url = step.url
        val expiresAt = step.expiresAt?.let(::parseRfc3339)
        if (url != null && (expiresAt == null || expiresAt - wallClock() >= REFRESH_BEFORE_MS)) {
            return presentStep(step, url, step.expiresAt)
        }
        runStep(step, REFRESH) { outcome ->
            val fresh = outcome.url
            if (outcome.outcome == RequirementsOutcome.OPEN_URL && fresh != null) presentStep(step, fresh, outcome.expiresAt) else ran(step)
        }
    }

    private fun presentStep(step: MeldReadyStep, url: String, expiresAt: String?) =
        present(step, url, expiresAt) { refreshAfterClose(step) }

    /** A 401 renews and sends the refresh again on the new session. The step stays attempted: the page is not shown again. */
    private fun refreshAfterClose(step: MeldReadyStep) {
        runStep(step, REFRESH, afterRenewal = { refreshAfterClose(step) }) { ran(step) }
    }

    /** A browser cannot start from an activity in the background, so a page reached then waits for its resume and a fresh READ. */
    private fun present(step: MeldReadyStep, url: String, expiresAt: String?, onClosed: () -> Unit) {
        if (!isHttpsPage(url)) return failWith(MeldReadyErrorKind.PROTOCOL)
        if (!progress(MeldReadyState.PRESENTING)) return
        val custom = handlers.presentUrl
        if (custom != null) {
            val closed = once { _: Unit -> onClosed() }
            return custom(MeldPresentUrlRequest(step.stepId, url, expiresAt)) { closed(Unit) }
        }
        val presenter = presenter ?: return resolve(MeldReadyStatus.ACTION_REQUIRED, listOf(step))
        presentation = presenter.whenResumed {
            presentation = null
            attempted.remove(step.stepId)
            if (!resolved) read()
        }
        if (presentation != null) return
        presentation = presenter.present(url) {
            presentation = null
            if (!resolved) onClosed()
        }
        if (presentation == null) onClosed()
    }

    // MARK: - Requests

    private fun runStep(
        step: MeldReadyStep,
        fields: Map<String, Any?>,
        onFieldErrors: ((List<MeldFieldError>) -> Unit)? = null,
        afterRenewal: (() -> Unit)? = null,
        onOutcome: (RequirementsOutcome) -> Unit,
    ) {
        if (!progress(MeldReadyState.RUNNING)) return
        send(RUN_STEP, fields + ("stepId" to step.stepId), newKey()) { result ->
            val answer = result.getOrNull()
            if (answer != null) {
                return@send onOutcome(RequirementsOutcome.parse(answer, step.stepId) ?: return@send failWith(MeldReadyErrorKind.PROTOCOL))
            }
            val error = result.exceptionOrNull()
            val code = (error as? PaymentActionException.Action)?.code
            val fieldErrors = (error as? PaymentActionException.Action)?.fieldErrors.orEmpty()
            when {
                code == PaymentActionFailureCode.INVALID_REQUEST && fieldErrors.isNotEmpty() && onFieldErrors != null ->
                    onFieldErrors(fieldErrors.map { MeldFieldError(it.key, it.reason) })
                status(error) == 409 || status(error) == 422 -> ran(step)
                code == PaymentActionFailureCode.OUTCOME_UNKNOWN -> {
                    fun reread(fresh: RequirementsSnapshot) {
                        val offered = fresh.next.firstOrNull { it.stepId == step.stepId } ?: return onSnapshot(fresh)
                        snapshot = fresh
                        // Only a page refresh runs again: any other step may have written, and Meld holds it meanwhile.
                        if (offered.kind != OPEN_URL) return resolve(MeldReadyStatus.ACTION_REQUIRED, listOf(offered))
                        val resends = (outcomeUnknown[step.stepId] ?: 0) + 1
                        if (resends > MAX_OUTCOME_UNKNOWN) return fail(error)
                        outcomeUnknown[step.stepId] = resends
                        attempted[step.stepId] = fresh.fingerprint
                        runStep(step, fields, onFieldErrors, afterRenewal, onOutcome)
                    }
                    // A renewal reads on the new session and still decides here.
                    read(afterRenewal ?: { read(then = ::reread) }, ::reread)
                }
                status(error) == 401 -> {
                    if (afterRenewal == null) attempted.remove(step.stepId)
                    fail(error, afterRenewal)
                }
                else -> fail(error)
            }
        }
    }

    /** Sends with the transport retries: the same key after a transport failure or a 425, so a duplicate is never a new attempt. */
    private fun send(
        operation: String,
        fields: Map<String, Any?>,
        key: UUID?,
        attempt: Int = 0,
        inFlight: Int = 0,
        onAnswer: (Result<Map<String, Any?>>) -> Unit,
    ) {
        val current = transport ?: return
        current.send(operation, fields, key) { result ->
            if (resolved || current !== transport) return@send
            val error = result.exceptionOrNull()
            when {
                error === PaymentActionException.Transport && attempt < TRANSPORT_BACKOFF_MS.size ->
                    after(TRANSPORT_BACKOFF_MS[attempt]) { send(operation, fields, key, attempt + 1, inFlight, onAnswer) }
                status(error) == 425 && inFlight < MAX_IN_FLIGHT_RETRIES ->
                    after(IN_FLIGHT_RETRY_MS) { send(operation, fields, key, attempt, inFlight + 1, onAnswer) }
                else -> {
                    if (error == null) renewedSinceAnswer = false
                    onAnswer(result)
                }
            }
        }
    }

    private fun fail(error: Throwable?, afterRenewal: (() -> Unit)? = null) {
        when {
            status(error) == 401 -> renew(afterRenewal)
            error === PaymentActionException.Transport -> failWith(MeldReadyErrorKind.TRANSPORT)
            error is PaymentActionException.Action -> failWith(MeldReadyErrorKind.HTTP, error.code.httpStatus, error.code.name)
            error is PaymentActionException.Http -> failWith(MeldReadyErrorKind.HTTP, error.status)
            else -> failWith(MeldReadyErrorKind.PROTOCOL)
        }
    }

    /** Carries on with [then] on the new session, or with a READ. */
    private fun renew(then: (() -> Unit)? = null) {
        val renew = handlers.renewSession
        if (renew == null || renewedSinceAnswer || renewals >= MAX_RENEWALS) return resolve(MeldReadyStatus.EXPIRED)
        renewedSinceAnswer = true
        renewals += 1
        renew(once { session: MeldRequirementsSession? ->
            if (session == null) return@once resolve(MeldReadyStatus.EXPIRED)
            if (!use(session)) return@once
            if (then != null) then() else read()
        })
    }

    /**
     * Talks to [session] from now on. The mint declares a relative endpoint, so it resolves against the
     * configured environment and a session minted in another one cannot be told apart here: the server refuses
     * its bearer with 401, and the run ends EXPIRED after the one renewal. Only a declared endpoint that is not
     * on the configured API host (never a mint's) fails the run.
     */
    private fun use(session: MeldRequirementsSession): Boolean {
        val descriptor = try {
            RequirementsSessionDescriptor.parse(session.raw, environment)
        } catch (_: PaymentActionException) {
            Log.w(TAG, "requirements session endpoint is not on the '${environment.raw}' API host; pass the mint through unchanged.")
            failWith(MeldReadyErrorKind.PROTOCOL)
            return false
        }
        transport?.close()
        transport = connect(descriptor)
        return true
    }

    // MARK: - Timers, handlers and the result

    private fun poll(delayMs: Long) {
        if (!progress(MeldReadyState.WAITING)) return
        after(delayMs) { read() }
        polling = true
    }

    private fun after(delayMs: Long, work: () -> Unit) {
        cancelTimer()
        timer = main.schedule(delayMs) {
            timer = null
            polling = false
            if (!resolved) work()
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
        polling = false
    }

    /** False when [MeldReadyHandlers.onProgress] cancelled the run, so nothing else is called. */
    private fun progress(state: MeldReadyState): Boolean {
        handlers.onProgress?.invoke(MeldReadyProgress(state, snapshot?.raw))
        return !resolved
    }

    /** A handler's answer: taken once, from any thread, delivered on main and only while the run is going. */
    private fun <T> once(deliver: (T) -> Unit): (T) -> Unit {
        val answered = AtomicBoolean(false)
        return { value -> if (answered.compareAndSet(false, true)) onMain { if (!resolved) deliver(value) } }
    }

    private fun onMain(work: () -> Unit) {
        if (main.isCurrentThread) work() else main.schedule(0, work)
    }

    private fun failWith(kind: MeldReadyErrorKind, status: Int? = null, code: String? = null) =
        resolve(MeldReadyStatus.FAILED, error = MeldReadyError(kind, status, code))

    /** Delivers the result once and lets go of the handlers and the presenter, so a kept handle holds no activity. */
    private fun resolve(status: MeldReadyStatus, pending: List<MeldReadyStep>? = null, error: MeldReadyError? = null) {
        if (resolved) return
        resolved = true
        cancelTimer()
        presentation?.cancel()
        presentation = null
        transport?.close()
        unbind?.invoke()
        unbind = null
        val read = snapshot
        val deliver = onResult
        handlers = MeldReadyHandlers()
        presenter = null
        onResult = null
        deliver?.invoke(MeldReadyResult(status, read?.results().orEmpty(), pending, read?.continuation().orEmpty(), error))
    }

    companion object {
        private const val TAG = "MeldSDK"

        const val REFRESH_BEFORE_MS = 60_000L
        const val SETTLE_WINDOW_MS = 30_000L
        val TRANSPORT_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000)
        const val IN_FLIGHT_RETRY_MS = 1_000L
        const val MAX_IN_FLIGHT_RETRIES = 5
        const val MAX_RECOLLECTS = 3
        const val MAX_OUTCOME_UNKNOWN = 3
        const val MAX_RENEWALS = 3

        const val SUBMIT = "SUBMIT"
        const val COLLECT = "COLLECT"
        const val AGREE = "AGREE"
        const val OPEN_URL = "OPEN_URL"
        const val VERIFY_CONTACT = "VERIFY_CONTACT"
        private val KINDS = setOf(SUBMIT, COLLECT, AGREE, OPEN_URL, VERIFY_CONTACT)
        private val REFRESH = mapOf("refresh" to true)

        private fun status(error: Throwable?): Int? = when (error) {
            is PaymentActionException.Action -> error.code.httpStatus
            is PaymentActionException.Http -> error.status
            else -> null
        }
    }
}
