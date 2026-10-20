package io.meld.sdk.hosted

import android.view.ViewGroup
import io.meld.sdk.MeldError
import io.meld.sdk.MeldErrorCode
import io.meld.sdk.MeldEvent
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldProviderSession
import io.meld.sdk.MeldStatus

/** The page a hosted session drives. `WebViewHost` in production. */
internal interface HostedPage {
    fun mount(host: ViewGroup)

    fun unmount()

    fun evaluateJavascript(script: String, callback: (String?) -> Unit)
}

/** Where the page is attached. [OffscreenSurfaceHost] in production. */
internal interface HostedSurface {
    val container: ViewGroup
    val foreground: HostForeground
    var onHostGone: (() -> Unit)?

    fun remove()
}

/**
 * One provider-hosted wallet payment: loads the page offscreen, presses its pay button at most once,
 * bounds the wait for an outcome, and delivers the terminal callback before tearing down. A submitted
 * session outlives its handle in [DetachedSurfaces]. Main thread.
 */
internal class HostedWalletSession(
    private val orderId: String?,
    private val handlers: MeldEventHandlers,
    private val profile: HostedLinkProfile,
    private val presentScript: String,
    private val timing: Timing = Timing(),
    private val scheduler: Scheduler = MainScheduler,
    private val surfaces: DetachedSurfaces = DetachedSurfaces.shared,
) : MeldProviderSession, DetachedSurface {

    data class Timing(
        val loadTimeoutMs: Long = 20_000,
        val pressIntervalMs: Long = 250,
        val pressAttempts: Int = 20,
        val pressTimeoutMs: Long = 10_000,
        val presentationDeadlineMs: Long = 8_000,
        val presentationCeilingMs: Long = 180_000,
        val detachedGraceMs: Long = 60_000,
    )

    private enum class Phase { LOADING, PRESSING, PRESENTED, SUBMITTED, ENDED }

    private var page: HostedPage? = null
    private var surface: HostedSurface? = null
    private var deadline: PresentationDeadline? = null
    private var ceiling: PresentationCeiling? = null
    private var phase = Phase.LOADING
    private var pressStarted = false
    private var detached = false
    private var presses = 0
    private var pressInFlight = false
    private var loadTimeout: Cancellable? = null
    private var nextPress: Cancellable? = null
    private var pressTimeout: Cancellable? = null

    fun start(page: HostedPage, surface: HostedSurface) {
        this.page = page
        this.surface = surface
        deadline = PresentationDeadline(timing.presentationDeadlineMs, scheduler, surface.foreground)
        ceiling = PresentationCeiling(timing.presentationCeilingMs, scheduler, surface.foreground)
        surface.onHostGone = ::hostGone
        page.mount(surface.container)
        loadTimeout = scheduler.schedule(timing.loadTimeoutMs) {
            fail(MeldErrorCode.PRESENTATION_FAILED, "The provider's page did not load.", "load_timeout")
        }
    }

    // MARK: - Page input

    fun receive(message: Map<String, Any?>) {
        if (phase == Phase.ENDED) return
        if (phase == Phase.PRESENTED && profile.isProviderMessage(message)) deadline?.disarm()
        profile.interpret(message, orderId).forEach(::handle)
    }

    fun pageFailed(error: MeldError) {
        val detail = listOfNotNull(error.code, error.detail).joinToString(":")
        val clickMayHaveRun = phase == Phase.PRESENTED || (phase == Phase.PRESSING && pressInFlight)
        val code = if (clickMayHaveRun) MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN else MeldErrorCode.PRESENTATION_FAILED
        handle(MeldEvent.Error(MeldError(orderId, code.raw, error.message, detail, recoverable = false)))
    }

    fun pageTerminated() {
        when (phase) {
            Phase.ENDED -> return
            Phase.LOADING, Phase.PRESSING -> failBeforePresentation(
                "The provider's page stopped before it presented the payment sheet.",
                "render_process_gone",
            )
            Phase.PRESENTED, Phase.SUBMITTED -> {
                settle("render_process_gone")
                handlers.sessionEnded?.invoke(orderId)
            }
        }
    }

    // MARK: - Lifecycle

    override fun unmount() {
        if (!scheduler.isCurrentThread) {
            scheduler.schedule(0, ::unmount)
            return
        }
        when (phase) {
            Phase.ENDED -> return
            Phase.SUBMITTED -> if (!detached) {
                detached = true
                surfaces.keep(this, timing.detachedGraceMs)
            }
            Phase.LOADING, Phase.PRESSING, Phase.PRESENTED -> tearDown()
        }
    }

    override fun tearDown() {
        if (phase == Phase.ENDED) return
        phase = Phase.ENDED
        stopTimers()
        page?.unmount()
        page = null
        surface?.remove()
        surface = null
    }

    private fun hostGone() {
        when (phase) {
            Phase.ENDED -> return
            Phase.LOADING, Phase.PRESSING -> failBeforePresentation(
                "The screen hosting the payment went away before the payment sheet opened.",
                "host_detached",
            )
            Phase.PRESENTED -> {
                tearDown()
                handlers.sessionEnded?.invoke(orderId)
            }
            Phase.SUBMITTED -> unmount()
        }
    }

    private fun handle(event: MeldEvent) {
        when (phase) {
            Phase.ENDED -> return
            Phase.SUBMITTED -> return afterSubmission(event)
            Phase.LOADING, Phase.PRESSING, Phase.PRESENTED -> Unit
        }
        when (event) {
            is MeldEvent.Ready -> autoPresent()
            is MeldEvent.PaymentSubmitted -> {
                submit()
                dispatch(event)
            }
            is MeldEvent.StatusChange -> {
                if (event.change.status != MeldStatus.COMPLETED) return dispatch(event)
                submit()
                dispatch(event)
                settle(event.change.providerStatus ?: event.change.status.raw)
            }
            is MeldEvent.Cancel, is MeldEvent.Error -> {
                dispatch(event)
                tearDown()
            }
        }
    }

    private fun afterSubmission(event: MeldEvent) {
        if (!detached) dispatch(event)
        when (event) {
            is MeldEvent.StatusChange -> if (event.change.status == MeldStatus.COMPLETED) {
                settle(event.change.providerStatus ?: event.change.status.raw)
            }
            is MeldEvent.Cancel -> settle("cancel")
            is MeldEvent.Error -> settle(event.error.detail ?: event.error.code)
            is MeldEvent.Ready, is MeldEvent.PaymentSubmitted -> Unit
        }
    }

    private fun submit() {
        phase = Phase.SUBMITTED
        stopTimers()
    }

    private fun settle(outcome: String) {
        if (detached) surfaces.release(this, outcome)
        tearDown()
    }

    // MARK: - Pressing the page's pay button

    private fun autoPresent() {
        if (pressStarted) return
        pressStarted = true
        loadTimeout?.cancel()
        phase = Phase.PRESSING
        pressTimeout = scheduler.schedule(timing.pressTimeoutMs) {
            failBeforePresentation("The provider's page did not answer the press.", "press_timeout")
        }
        press()
    }

    private fun press() {
        val page = page ?: return
        if (phase != Phase.PRESSING) return
        presses += 1
        pressInFlight = true
        page.evaluateJavascript(presentScript, ::pressed)
    }

    private fun pressed(result: String?) {
        if (phase != Phase.PRESSING) return
        pressInFlight = false
        when {
            result == "clicked" -> presented()
            presses < timing.pressAttempts -> nextPress = scheduler.schedule(timing.pressIntervalMs, ::press)
            else -> fail(
                MeldErrorCode.PRESENTATION_FAILED,
                "The provider's page did not offer its pay button.",
                "pay_button_not_found",
            )
        }
    }

    private fun presented() {
        phase = Phase.PRESENTED
        pressTimeout?.cancel()
        pressTimeout = null
        val sheetUp = surface?.foreground?.resumed == false
        if (!sheetUp) {
            deadline?.arm {
                fail(MeldErrorCode.PRESENTATION_FAILED, "The payment sheet did not appear.", "presentation_deadline")
            }
        }
        ceiling?.arm(paused = sheetUp) {
            fail(MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN, UNKNOWN_OUTCOME, "presentation_ceiling")
        }
        handlers.onReady?.invoke(orderId)
    }

    // MARK: - Helpers

    private fun fail(code: MeldErrorCode, message: String, detail: String) {
        handle(MeldEvent.Error(MeldError(orderId, code.raw, message, detail, recoverable = false)))
    }

    /** A press whose answer has not arrived may already have clicked, so its interruption is no proof of no attempt. */
    private fun failBeforePresentation(message: String, detail: String) =
        if (pressInFlight) {
            fail(MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN, UNKNOWN_OUTCOME, detail)
        } else {
            fail(MeldErrorCode.PRESENTATION_FAILED, message, detail)
        }

    private fun stopTimers() {
        loadTimeout?.cancel()
        loadTimeout = null
        nextPress?.cancel()
        nextPress = null
        pressTimeout?.cancel()
        pressTimeout = null
        deadline?.disarm()
        ceiling?.disarm()
    }

    private fun dispatch(event: MeldEvent) {
        when (event) {
            is MeldEvent.Ready -> handlers.onReady?.invoke(orderId)
            is MeldEvent.PaymentSubmitted -> handlers.onPaymentSubmitted?.invoke(orderId)
            is MeldEvent.StatusChange -> handlers.onStatusChange?.invoke(event.change)
            is MeldEvent.Cancel -> handlers.onCancel?.invoke(orderId)
            is MeldEvent.Error -> handlers.onError?.invoke(event.error)
        }
    }
}

private const val UNKNOWN_OUTCOME = "The provider did not report an outcome. Track the existing order without paying again."
