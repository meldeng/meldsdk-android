package io.meld.sdk

import android.content.Context
import android.util.Log
import io.meld.sdk.googlepay.GooglePayReadiness
import io.meld.sdk.googlepay.GooglePayReadinessSpec
import io.meld.sdk.hosted.Cancellable
import io.meld.sdk.hosted.Scheduler

/**
 * Whether this device can pay with a method, answered before any order exists. Advisory: a mounted
 * order that cannot pay still reports [MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE].
 */
sealed class MeldReadiness {
    /** Offer the method. */
    data object Ready : MeldReadiness()

    /** Do not offer the method. [code] uses the same terms a mounted order would. */
    data class Unavailable(val code: MeldErrorCode, val reason: String) : MeldReadiness()

    /**
     * The SDK could not tell, or the method has no device check (a card). You may offer the method; the
     * payment itself has the final word.
     */
    data class Unknown(val reason: String) : MeldReadiness()
}

/** Readiness reasons worded the same on every Meld SDK. */
internal object MeldReadinessReason {
    const val NO_DEVICE_CHECK = "This payment method has no device check; offer it and let the payment decide."
    const val APPLE_PAY_NOT_ON_ANDROID = "Apple Pay is not available on Android."
    const val GOOGLE_PAY_NO_USABLE_CARD = "Google Pay has no usable card on this device."
    const val GOOGLE_PAY_NOT_SUPPORTED = "Google Pay is not supported on this device."
}

/** One readiness question, as an adapter receives it. */
internal data class ReadinessQuery(
    val context: Context,
    val presentation: MeldHeadlessPresentation,
    val paymentMethodType: String,
)

/** The answer of an adapter with no device check: it says whether the SDK presents the method, not whether the device can pay. */
internal fun capabilityReadiness(capabilities: MeldCapabilities): MeldReadiness =
    if (capabilities.surface == "unsupported") {
        MeldReadiness.Unavailable(
            MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE,
            "This SDK cannot present this payment method on this device.",
        )
    } else {
        MeldReadiness.Unknown(MeldReadinessReason.NO_DEVICE_CHECK)
    }

/** Asks [adapter] and delivers exactly one answer on [scheduler]; no answer within [timeoutMs] is [MeldReadiness.Unknown]. */
internal fun askReadiness(
    adapter: MeldAdapter?,
    query: ReadinessQuery,
    scheduler: Scheduler,
    timeoutMs: Long,
    onResult: (MeldReadiness) -> Unit,
) {
    val asked = "${query.paymentMethodType} via ${query.presentation.surface}/${query.presentation.protocol} v${query.presentation.version}"
    answerOnce(asked, scheduler, timeoutMs, onResult) { answer ->
        if (adapter == null) {
            answer(MeldReadiness.Unavailable(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, "This SDK does not present $asked."))
        } else {
            adapter.readiness(query, answer)
        }
    }
}

/** Whether this device can pay with a payment method at all, before any provider or quote row is chosen. */
internal class DeviceReadiness(private val googlePay: GooglePayReadiness = GooglePayReadiness()) {
    /** Delivers exactly one answer on [scheduler]; no answer within [timeoutMs] is [MeldReadiness.Unknown]. */
    fun ask(context: Context, paymentMethodType: String, scheduler: Scheduler, timeoutMs: Long, onResult: (MeldReadiness) -> Unit) =
        answerOnce(paymentMethodType, scheduler, timeoutMs, onResult) { answer -> check(context, paymentMethodType, answer) }

    private fun check(context: Context, paymentMethodType: String, onResult: (MeldReadiness) -> Unit) =
        when (paymentMethodType) {
            "GOOGLE_PAY" -> googlePay.check(context, GooglePayReadinessSpec.anyCard, onResult)
            "APPLE_PAY" -> onResult(
                MeldReadiness.Unavailable(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE, MeldReadinessReason.APPLE_PAY_NOT_ON_ANDROID),
            )
            else -> onResult(MeldReadiness.Unknown(MeldReadinessReason.NO_DEVICE_CHECK))
        }
}

private fun answerOnce(
    asked: String,
    scheduler: Scheduler,
    timeoutMs: Long,
    onResult: (MeldReadiness) -> Unit,
    check: (answer: (MeldReadiness) -> Unit) -> Unit,
) {
    var answered = false
    var timeout: Cancellable? = null
    fun deliver(readiness: MeldReadiness) {
        if (answered) {
            Log.d(READINESS_TAG, "dropped a readiness answer for $asked after the first: $readiness")
            return
        }
        answered = true
        timeout?.cancel()
        if (readiness is MeldReadiness.Unknown) Log.i(READINESS_TAG, "readiness for $asked is unknown: ${readiness.reason}")
        onResult(readiness)
    }
    timeout = scheduler.schedule(timeoutMs) { deliver(MeldReadiness.Unknown("No readiness answer within $timeoutMs ms.")) }
    val answer: (MeldReadiness) -> Unit = { readiness -> scheduler.schedule(0) { deliver(readiness) } }

    try {
        check(answer)
    } catch (e: Exception) {
        answer(MeldReadiness.Unknown("The readiness check failed: ${e.message ?: e.javaClass.simpleName}"))
    } catch (e: LinkageError) {
        answer(MeldReadiness.Unknown("This app lacks a library the readiness check needs: ${e.message ?: e.javaClass.simpleName}"))
    }
}

private const val READINESS_TAG = "MeldSDK"
