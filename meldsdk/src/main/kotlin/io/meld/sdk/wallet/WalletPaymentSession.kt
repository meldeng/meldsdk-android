package io.meld.sdk.wallet

import io.meld.sdk.MeldError
import io.meld.sdk.MeldErrorCode
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldProviderSession
import io.meld.sdk.MeldStatus
import io.meld.sdk.MeldStatusChange
import io.meld.sdk.actions.AttemptRecord
import io.meld.sdk.actions.AttemptStore
import io.meld.sdk.actions.MountOwnership
import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.actions.PaymentActionSending
import io.meld.sdk.wallet.WalletActionResponse.State
import java.net.URI

/**
 * Presents the wallet sheet and returns its surface. [submit] hands over the customer's payment once and
 * hears whether the server accepted it; [finished] says the sheet is gone. A sheet that closes without a
 * payment reports its own cancel or error first. Throws [WalletUnavailableException] when the device cannot pay.
 */
internal typealias WalletSheetFactory =
    (submit: (WalletPayment, (accepted: Boolean) -> Unit) -> Unit, finished: () -> Unit) -> MeldProviderSession

/**
 * Presents a hosted verification behind the customer's confirmation and returns its surface. At the
 * customer's Continue, [open] claims the verification and answers whether to open the page; [closed]
 * says whether it was opened.
 */
internal typealias HostedVerificationFactory =
    (WalletVerification, open: (proceed: (Boolean) -> Unit) -> Unit, closed: (opened: Boolean) -> Unit) -> MeldProviderSession

/** The wallet cannot pay on this device, and no attempt exists. [reason] is the readiness wording. */
internal class WalletUnavailableException(val reason: String) : Exception(reason)

/**
 * The order lifecycle shared by every token wallet: read the server's state, present the sheet at most
 * once, claim before submitting, and resolve an ambiguous answer with a read, never a second submission.
 * Adapters supply the sheet and the hosted surface, never financial retry policy. Store I/O and HTTP run
 * in order on [actions]' thread; everything else, including every callback, runs on main.
 */
internal class WalletPaymentSession(
    private val identity: String,
    private val orderId: String?,
    private val actions: PaymentActionSending,
    private val store: AttemptStore,
    private val handlers: MeldEventHandlers,
    private val sheetFactory: WalletSheetFactory,
    private val hostedFactory: HostedVerificationFactory,
    private val acceptsVerification: (URI) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : MeldProviderSession {

    /** What one job learned: the server's answer with this device's fences after it, or which part failed. */
    private sealed class Snapshot {
        class Read(val response: WalletActionResponse, val record: AttemptRecord) : Snapshot()
        object StateUnavailable : Snapshot()
        object StoreFailed : Snapshot()
        class NotClaimed(val error: Exception) : Snapshot()
    }

    private var active = true
    private var started = false
    private var sheetActive = false
    private var didAuthorize = false
    private var submitting = false
    private var surface: MeldProviderSession? = null
    private var pending: Snapshot? = null
    private var submission: ((Boolean) -> Unit)? = null

    init {
        if (!MountOwnership.acquire(identity)) throw PaymentActionException.AlreadyAttempted
    }

    /** Reads the server's state. Post it from mount, so mount returns its handle before any callback. */
    fun start() {
        if (!active || started) return
        started = true
        read()
    }

    /** Work in flight completes silently: a claimed submission still reaches the server, and a remount reads it. */
    override fun unmount() {
        if (!active) return
        active = false
        surface?.unmount()
        surface = null
        pending = null
        submission?.let { completion ->
            submission = null
            completion(false)
        }
        actions.finish()
        MountOwnership.release(identity)
    }

    /** The host Activity went away. The session ends without an outcome of its own, so the gate reports one. */
    fun hostGone() {
        if (!active) return
        unmount()
        handlers.sessionEnded?.invoke(orderId)
    }

    private fun read() {
        actions.run(::readState) { apply(it.getOrElse { Snapshot.StateUnavailable }) }
    }

    private fun readState(): Snapshot {
        val response = try {
            WalletActionResponse.decode(actions.execute(READ_SUBMISSION, emptyMap(), null))
        } catch (_: Exception) {
            return Snapshot.StateUnavailable
        }
        return snapshot(response)
    }

    private fun snapshot(response: WalletActionResponse): Snapshot = try {
        if (response.state != State.NOT_STARTED) store.observeSubmission()
        Snapshot.Read(response, store.record())
    } catch (_: Exception) {
        Snapshot.StoreFailed
    }

    private fun apply(snapshot: Snapshot) {
        if (!active) return
        when (snapshot) {
            Snapshot.StateUnavailable -> fail(
                "PAYMENT_STATE_UNAVAILABLE",
                "Payment state is unavailable. Review the existing order before trying another payment.",
            )
            Snapshot.StoreFailed, is Snapshot.NotClaimed -> fail(CONTINUATION_UNAVAILABLE, CONTINUATION_MESSAGE)
            is Snapshot.Read -> present(snapshot.response, snapshot.record)
        }
    }

    private fun present(response: WalletActionResponse, record: AttemptRecord) {
        when (response.state) {
            State.NOT_STARTED -> {
                if (record.submissionStarted) {
                    fail(MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN.raw, ALREADY_ATTEMPTED_MESSAGE)
                    return
                }
                sheetActive = true
                install(::sheetFailed) {
                    sheetFactory({ payment, completion ->
                        if (active) submit(payment, completion) else completion(false)
                    }, ::sheetFinished)
                }
            }
            State.SUBMITTED -> {
                pendingStatus()
                if (!active) return
                unmount()
                handlers.onPaymentSubmitted?.invoke(orderId)
            }
            State.VERIFICATION_REQUIRED -> verify(response.verification, record)
            State.IN_PROGRESS, State.UNKNOWN -> {
                pendingStatus()
                fail(
                    MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN.raw,
                    "The payment is being resolved. Track the existing order without submitting again.",
                )
            }
            State.EXPIRED -> {
                pendingStatus()
                fail(VERIFICATION_WINDOW_EXPIRED, EXPIRED_MESSAGE)
            }
            State.FAILED -> fail(
                MeldErrorCode.PAYMENT_REJECTED.raw,
                "The payment attempt failed. Review the order before starting another payment.",
            )
        }
    }

    private fun verify(verification: WalletVerification?, record: AttemptRecord) {
        if (verification == null || !acceptsVerification(verification.url)) {
            fail("INVALID_VERIFICATION", "The verification response cannot be presented.")
            return
        }
        if (verification.expiresAtMillis <= now()) {
            fail(VERIFICATION_WINDOW_EXPIRED, EXPIRED_MESSAGE)
            return
        }
        if (record.verificationOpened) {
            fail("WAIT_FOR_PAYMENT", "Verification was already opened. Track the existing order's payment status.")
            return
        }
        pendingStatus()
        if (!active) return
        install({ fail(CONTINUATION_UNAVAILABLE, CONTINUATION_MESSAGE) }) {
            hostedFactory(verification, { proceed -> openVerification(verification, proceed) }, ::verificationClosed)
        }
    }

    private fun openVerification(verification: WalletVerification, proceed: (Boolean) -> Unit) {
        if (!active) {
            proceed(false)
            return
        }
        if (verification.expiresAtMillis <= now()) {
            fail(VERIFICATION_WINDOW_EXPIRED, EXPIRED_MESSAGE)
            proceed(false)
            return
        }
        actions.run({ store.claimVerification() }) { claimed ->
            if (!active) return@run
            if (claimed.isSuccess) {
                proceed(true)
            } else {
                fail("VERIFICATION_UNAVAILABLE", "Review the existing order before continuing.")
                proceed(false)
            }
        }
    }

    private fun verificationClosed(opened: Boolean) {
        if (!active) return
        surface = null
        if (opened) {
            read()
        } else {
            unmount()
            handlers.onCancel?.invoke(orderId)
        }
    }

    private fun submit(payment: WalletPayment, completion: (Boolean) -> Unit) {
        if (didAuthorize) {
            completion(false)
            return
        }
        didAuthorize = true
        val fields = try {
            payment.actionFields()
        } catch (e: Exception) {
            completion(false)
            notSubmitted(e)
            return
        }
        submitting = true
        submission = completion
        actions.run({ submitState(fields) }) { completeSubmission(it.getOrElse { Snapshot.StateUnavailable }) }
    }

    private fun submitState(fields: Map<String, Any?>): Snapshot {
        val key = try {
            store.claimSubmission()
        } catch (e: Exception) {
            return Snapshot.NotClaimed(e)
        }
        val answer = try {
            WalletActionResponse.decode(actions.execute(SUBMIT_WALLET_PAYMENT, fields, key))
        } catch (_: Exception) {
            null
        }
        if (answer != null && answer.recognized) return snapshot(answer)
        // A lost, malformed or unrecognized answer is ambiguous. Recovery is a read, never another submission.
        return readState()
    }

    private fun completeSubmission(snapshot: Snapshot) {
        if (!active) return
        submitting = false
        val completion = submission
        submission = null
        if (snapshot is Snapshot.NotClaimed) {
            completion?.invoke(false)
            notSubmitted(snapshot.error)
            return
        }
        pending = snapshot
        completion?.invoke(snapshot is Snapshot.Read && snapshot.response.state in ACCEPTED)
        if (!sheetActive) drain()
    }

    private fun sheetFinished() {
        if (!active) return
        sheetActive = false
        surface = null
        if (pending != null) {
            drain()
        } else if (!submitting) {
            unmount()
        }
    }

    private fun drain() {
        if (!active) return
        val snapshot = pending ?: return
        pending = null
        apply(snapshot)
    }

    private inline fun install(failed: (Exception) -> Unit, make: () -> MeldProviderSession) {
        val created = try {
            make()
        } catch (e: Exception) {
            failed(e)
            return
        }
        if (active) surface = created else created.unmount()
    }

    /** The sheet never came up, so nothing was claimed or sent. */
    private fun sheetFailed(error: Exception) {
        if (error is WalletUnavailableException) {
            fail(MeldErrorCode.PAYMENT_METHOD_UNAVAILABLE.raw, error.reason)
        } else {
            fail(MeldErrorCode.PRESENTATION_FAILED.raw, SHEET_FAILED_MESSAGE, detail = CONTINUATION_UNAVAILABLE)
        }
    }

    /** The payment failed validation or its claim, so nothing was sent, unless the device already held the claim. */
    private fun notSubmitted(error: Exception) {
        if (error == PaymentActionException.AlreadyAttempted) {
            fail(MeldErrorCode.PAYMENT_OUTCOME_UNKNOWN.raw, ALREADY_ATTEMPTED_MESSAGE, detail = "submission_already_claimed")
        } else {
            fail(MeldErrorCode.PRESENTATION_FAILED.raw, NOT_SUBMITTED_MESSAGE, detail = NOT_SUBMITTED)
        }
    }

    private fun pendingStatus() {
        handlers.onStatusChange?.invoke(MeldStatusChange(orderId, MeldStatus.PENDING, providerStatus = null, raw = null))
    }

    private fun fail(code: String, message: String, detail: String? = null) {
        if (!active) return
        unmount()
        handlers.onError?.invoke(MeldError(orderId, code, message, detail, recoverable = false))
    }

    private companion object {
        const val READ_SUBMISSION = "READ_SUBMISSION"
        const val SUBMIT_WALLET_PAYMENT = "SUBMIT_WALLET_PAYMENT"
        const val CONTINUATION_UNAVAILABLE = "PAYMENT_CONTINUATION_UNAVAILABLE"
        const val CONTINUATION_MESSAGE = "The existing order cannot be continued on this device right now."
        const val VERIFICATION_WINDOW_EXPIRED = "VERIFICATION_WINDOW_EXPIRED"
        const val EXPIRED_MESSAGE = "The verification window expired. Review the existing order."
        const val NOT_SUBMITTED = "PAYMENT_NOT_SUBMITTED"
        const val NOT_SUBMITTED_MESSAGE = "Payment details could not be validated or recorded. No payment was attempted."
        const val SHEET_FAILED_MESSAGE = "The payment sheet could not be presented. No payment was attempted."
        const val ALREADY_ATTEMPTED_MESSAGE = "A payment was already attempted. Review the existing order."
        val ACCEPTED = setOf(State.SUBMITTED, State.VERIFICATION_REQUIRED, State.IN_PROGRESS, State.UNKNOWN)
    }
}
