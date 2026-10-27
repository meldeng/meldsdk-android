package io.meld.sdk.wallet

import io.meld.sdk.MeldError
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldProviderSession
import io.meld.sdk.MeldStatus
import io.meld.sdk.MeldStatusChange
import io.meld.sdk.actions.AttemptRecord
import io.meld.sdk.actions.AttemptStore
import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.actions.PaymentActionSending
import io.meld.sdk.actions.QueuedMain
import io.meld.sdk.gated
import io.meld.sdk.isRegisteredWidgetUrl
import io.meld.sdk.wallet.WalletFixtures.NOW
import io.meld.sdk.wallet.WalletFixtures.response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.concurrent.thread

/** Port of iOS `WalletPaymentSessionTests`, plus Android's own cases. The actions thread is stepped by hand. */
class WalletPaymentSessionTest {

    @Test fun preflight_then_exactly_one_submission_waits_for_sheet_dismissal() {
        val flow = Harness()
        assertEquals(0, flow.actions.queued)
        flow.session.start()
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        assertEquals(listOf("READ_SUBMISSION"), flow.actions.operations)
        assertEquals(1, flow.sheets)
        flow.authorize()
        flow.authorize()
        assertEquals(listOf(false), flow.outcomes)
        flow.actions.respond(response("SUBMITTED"))
        assertEquals(listOf("READ_SUBMISSION", "SUBMIT_WALLET_PAYMENT"), flow.actions.operations)
        assertEquals(flow.store.value.submissionKey, flow.actions.requests[1].key)
        assertTrue(flow.store.value.submissionStarted)
        assertEquals(listOf("read:READ_SUBMISSION", "store:record", "store:claim",
            "send:SUBMIT_WALLET_PAYMENT", "store:observe", "store:record"), flow.log)
        assertEquals(listOf(false, true), flow.outcomes)
        assertEquals("Submission UI must wait for the sheet to finish", 0, flow.submitted)
        flow.sheetFinished?.invoke()
        assertEquals(1, flow.submitted)
        assertEquals(listOf(MeldStatus.PENDING), flow.statuses.map { it.status })
        assertNull(flow.statuses.first().raw)
        assertEquals(1, flow.actions.finished)
    }

    @Test fun lost_or_malformed_submission_response_only_reads_and_never_resubmits() {
        for (malformed in listOf(false, true)) {
            val flow = Harness()
            flow.session.start()
            flow.actions.respond(response("NOT_STARTED"))
            flow.authorize()
            val answer: Any = if (malformed) mapOf("version" to 1, "status" to "NEW_UNKNOWN_STATE") else PaymentActionException.Transport
            flow.actions.respond(answer, response("NOT_STARTED"))
            assertEquals(listOf("READ_SUBMISSION", "SUBMIT_WALLET_PAYMENT", "READ_SUBMISSION"), flow.actions.operations)
            flow.sheetFinished?.invoke()
            assertEquals(1, flow.sheets)
            assertEquals(listOf("PAYMENT_OUTCOME_UNKNOWN"), flow.errors.map { it.code })
            assertFalse(flow.errors.single().recoverable)
            flow.session.unmount()
        }
    }

    @Test fun unrecognized_submission_answer_reads_and_never_reports_a_failed_payment() {
        val unrecognized = listOf(
            mapOf("version" to 1, "status" to "NEW_PROVIDER_STATE", "nextStep" to "WAIT_FOR_PROVIDER"),
            mapOf("version" to 1, "status" to "FAILED", "nextStep" to "WAIT_FOR_PROVIDER"),
            mapOf("version" to 1, "status" to "SUBMITTED", "nextStep" to "NEW_NEXT_STEP"),
        )
        for (answer in unrecognized) for (read in listOf(answer, response("SUBMITTED"))) {
            val flow = Harness(gated = true)
            flow.session.start()
            flow.actions.respond(response("NOT_STARTED"))
            flow.authorize()
            flow.actions.respond(answer, read)
            assertEquals(listOf("READ_SUBMISSION", "SUBMIT_WALLET_PAYMENT", "READ_SUBMISSION"), flow.actions.operations)
            assertEquals(listOf(true), flow.outcomes)
            flow.sheetFinished?.invoke()
            val resolved = read["status"] == "SUBMITTED" && read["nextStep"] == "WAIT_FOR_PAYMENT"
            assertEquals(if (resolved) emptyList() else listOf("PAYMENT_OUTCOME_UNKNOWN"), flow.errors.map { it.code })
            assertEquals(if (resolved) 1 else 0, flow.submitted)
            assertEquals(listOf(MeldStatus.PENDING), flow.statuses.map { it.status })
            assertEquals(1, flow.sheets)
            flow.session.unmount()
        }
    }

    @Test fun unresolved_submission_answer_completes_the_sheet_and_reports_an_unknown_outcome() {
        for (state in listOf("IN_PROGRESS", "UNKNOWN")) {
            val flow = Harness(gated = true)
            flow.session.start()
            flow.actions.respond(response("NOT_STARTED"))
            flow.authorize()
            flow.actions.respond(response(state))
            assertEquals(state, listOf("READ_SUBMISSION", "SUBMIT_WALLET_PAYMENT"), flow.actions.operations)
            assertEquals(state, listOf(true), flow.outcomes)
            flow.sheetFinished?.invoke()
            assertEquals(state, listOf("PAYMENT_OUTCOME_UNKNOWN"), flow.errors.map { it.code })
        }
        val failed = Harness(gated = true)
        failed.session.start()
        failed.actions.respond(response("NOT_STARTED"))
        failed.authorize()
        failed.actions.respond(response("FAILED"))
        assertEquals(listOf(false), failed.outcomes)
        failed.session.unmount()
    }

    @Test fun unrecognized_state_before_the_sheet_fences_the_order_and_reports_an_unknown_outcome() {
        val flow = Harness(gated = true)
        flow.session.start()
        flow.actions.respond(mapOf("version" to 1, "status" to "NOT_STARTED", "nextStep" to "NEW_NEXT_STEP"))
        assertEquals(0, flow.sheets)
        assertTrue(flow.store.value.submissionStarted)
        assertEquals(listOf("PAYMENT_OUTCOME_UNKNOWN"), flow.errors.map { it.code })
        assertTrue(flow.errors.single().attemptMayExist)
    }

    @Test fun remount_with_durable_attempt_never_presents_another_wallet() {
        val store = MemoryStore()
        val first = Harness(store = store)
        first.session.start()
        first.actions.respond(response("NOT_STARTED"))
        first.authorize()
        first.session.unmount()
        first.actions.respond(PaymentActionException.Transport, PaymentActionException.Transport)
        assertTrue(store.value.submissionStarted)

        val replay = Harness(identity = first.identity, store = store)
        replay.session.start()
        replay.actions.respond(response("NOT_STARTED"))
        assertEquals(0, replay.sheets)
        assertEquals(listOf("PAYMENT_OUTCOME_UNKNOWN"), replay.errors.map { it.code })
        assertEquals(listOf("READ_SUBMISSION"), replay.actions.operations)
    }

    @Test fun every_recorded_server_outcome_prevents_a_new_wallet_sheet() {
        for (state in listOf("SUBMITTED", "IN_PROGRESS", "UNKNOWN", "FAILED", "EXPIRED")) {
            val flow = Harness()
            flow.session.start()
            flow.actions.respond(response(state))
            assertEquals(state, 0, flow.sheets)
            assertTrue(state, flow.store.value.submissionStarted)
            assertEquals(state, if (state == "SUBMITTED") 1 else 0, flow.submitted)
            assertTrue(flow.errors.all { !it.recoverable })
            assertEquals(1, flow.actions.finished)
        }
    }

    @Test fun duplicate_mounts_are_rejected_until_ownership_is_released() {
        val first = Harness()
        val error = assertThrows(PaymentActionException::class.java) { Harness(identity = first.identity) }
        assertEquals(PaymentActionException.AlreadyAttempted, error)
        first.session.unmount()
        Harness(identity = first.identity).session.unmount()
    }

    @Test fun unmount_suppresses_late_read_and_submission_events_but_completes_authorization() {
        val early = Harness()
        early.session.start()
        early.session.unmount()
        early.actions.respond(response("NOT_STARTED"))
        assertEquals(0, early.sheets)

        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        flow.authorize()
        flow.session.unmount()
        assertEquals(listOf(false), flow.outcomes)
        flow.actions.respond(response("VERIFICATION_REQUIRED"))
        assertEquals(listOf(false), flow.outcomes)
        assertEquals(0, flow.hosted.size)
        assertEquals(0, flow.submitted)
        assertTrue(flow.errors.isEmpty())
        assertTrue(flow.statuses.isEmpty())
    }

    @Test fun an_unmount_from_another_thread_tears_down_on_main() {
        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        var completedOn: Thread? = null
        flow.submitWallet?.invoke(WalletFixtures.payment) { accepted ->
            flow.outcomes += accepted
            completedOn = Thread.currentThread()
        }
        thread { flow.session.unmount() }.join()
        assertFalse(flow.surfaces.single().unmounted)
        assertTrue(flow.outcomes.isEmpty())
        assertEquals(0, flow.actions.finished)

        assertEquals(1, flow.main.drain())
        assertTrue(flow.surfaces.single().unmounted)
        assertEquals(listOf(false), flow.outcomes)
        assertSame(Thread.currentThread(), completedOn)
        assertEquals(1, flow.actions.finished)
    }

    @Test fun sheet_dismissed_during_request_still_resolves_the_existing_attempt() {
        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        flow.authorize()
        flow.sheetFinished?.invoke()
        assertEquals(0, flow.actions.finished)
        flow.actions.respond(response("SUBMITTED"))
        assertEquals(1, flow.submitted)
    }

    @Test fun both_verification_dispositions_require_confirmation_and_can_open_only_once() {
        for (disposition in listOf("RESUME_PAYMENT", "REPLACE_PAYMENT_IN_HOSTED_FLOW")) {
            val flow = Harness()
            flow.session.start()
            flow.actions.respond(response("VERIFICATION_REQUIRED", disposition = disposition))
            assertEquals(0, flow.sheets)
            assertEquals(disposition, flow.hosted.first().disposition.name)
            assertFalse("Receiving a link is not opening it", flow.store.value.verificationOpened)
            assertEquals(true, flow.open())
            assertTrue(flow.store.value.verificationOpened)
            flow.closeVerification?.invoke(true)
            flow.actions.respond(response("VERIFICATION_REQUIRED", disposition = disposition))
            assertEquals(listOf("READ_SUBMISSION", "READ_SUBMISSION"), flow.actions.operations)
            assertEquals(1, flow.hosted.size)
            assertEquals(listOf("WAIT_FOR_PAYMENT"), flow.errors.map { it.code })
            assertEquals(0, flow.submitted)
        }
    }

    @Test fun verification_after_submission_waits_until_wallet_sheet_has_closed() {
        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        flow.authorize()
        flow.actions.respond(response("VERIFICATION_REQUIRED"))
        assertEquals(listOf(true), flow.outcomes)
        assertEquals(0, flow.hosted.size)
        flow.sheetFinished?.invoke()
        assertEquals(1, flow.hosted.size)
        assertEquals(0, flow.submitted)
        flow.session.unmount()
    }

    @Test fun cancelling_verification_does_not_read_or_reopen_it() {
        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("VERIFICATION_REQUIRED"))
        flow.closeVerification?.invoke(false)
        assertEquals(1, flow.cancelled)
        assertEquals(listOf("READ_SUBMISSION"), flow.actions.operations)
        assertFalse(flow.store.value.verificationOpened)
        assertEquals(1, flow.actions.finished)
        assertEquals(false, flow.open())
    }

    @Test fun expiry_is_checked_before_presentation_and_again_at_the_user_gesture() {
        val expired = Harness()
        expired.session.start()
        expired.actions.respond(response("VERIFICATION_REQUIRED", expires = "2030-03-17T17:46:40Z"))
        assertEquals(0, expired.hosted.size)
        assertEquals(listOf("VERIFICATION_WINDOW_EXPIRED"), expired.errors.map { it.code })

        val waiting = Harness()
        waiting.session.start()
        waiting.actions.respond(response("VERIFICATION_REQUIRED"))
        waiting.now = NOW + 301_000
        assertEquals(false, waiting.open())
        assertFalse(waiting.store.value.verificationOpened)
        assertEquals(listOf("VERIFICATION_WINDOW_EXPIRED"), waiting.errors.map { it.code })
    }

    @Test fun verification_that_expires_while_its_claim_is_written_is_not_opened() {
        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("VERIFICATION_REQUIRED"))
        flow.store.guard = { flow.now = NOW + 301_000 }
        assertEquals(false, flow.open())
        assertEquals(listOf("VERIFICATION_WINDOW_EXPIRED"), flow.errors.map { it.code })
        assertEquals(1, flow.actions.finished)
    }

    @Test fun a_repeated_continue_neither_claims_again_nor_ends_the_opened_verification() {
        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("VERIFICATION_REQUIRED"))
        val answers = mutableListOf<Boolean>()
        flow.openVerification?.invoke { answers += it }
        flow.openVerification?.invoke { answers += it }
        flow.actions.runJobs()
        flow.openVerification?.invoke { answers += it }
        flow.actions.runJobs()
        assertEquals(listOf(true), answers)
        assertEquals(1, flow.log.count { it == "store:claimVerification" })
        assertTrue(flow.errors.isEmpty())
        assertFalse(flow.surfaces.single().unmounted)
        assertEquals(0, flow.actions.finished)

        flow.closeVerification?.invoke(true)
        flow.actions.respond(response("SUBMITTED"))
        assertEquals(1, flow.submitted)
    }

    @Test fun storage_failure_blocks_wallet_and_verification_actions() {
        val flow = Harness()
        flow.store.fail = true
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        assertEquals(0, flow.sheets)
        assertEquals(listOf("PAYMENT_CONTINUATION_UNAVAILABLE"), flow.errors.map { it.code })

        val verification = Harness()
        verification.session.start()
        verification.actions.respond(response("VERIFICATION_REQUIRED"))
        verification.store.fail = true
        assertEquals(false, verification.open())
        assertEquals(listOf("VERIFICATION_UNAVAILABLE"), verification.errors.map { it.code })

        val claim = Harness()
        claim.session.start()
        claim.actions.respond(response("NOT_STARTED"))
        claim.store.fail = true
        claim.authorize()
        claim.actions.runJobs()
        assertEquals(listOf(false), claim.outcomes)
        assertEquals(listOf("READ_SUBMISSION"), claim.actions.operations)
        assertEquals(listOf("PRESENTATION_FAILED"), claim.errors.map { it.code })
        assertEquals("PAYMENT_NOT_SUBMITTED", claim.errors.single().detail)
        assertFalse(claim.errors.single().attemptMayExist)
    }

    @Test fun storage_failing_after_the_submission_claim_keeps_the_server_answer() {
        val cases = listOf(
            Triple("submitted", listOf<Any>(response("SUBMITTED")), null),
            Triple("lost then submitted", listOf(PaymentActionException.Transport, response("SUBMITTED")), null),
            Triple("verification", listOf<Any>(response("VERIFICATION_REQUIRED")), "PAYMENT_CONTINUATION_UNAVAILABLE"),
            Triple("not started", listOf<Any>(response("NOT_STARTED")), "PAYMENT_OUTCOME_UNKNOWN"),
        )
        for ((case, answers, error) in cases) {
            val flow = Harness(gated = true)
            flow.session.start()
            flow.actions.respond(response("NOT_STARTED"))
            flow.store.guard = { flow.store.fail = flow.store.value.submissionStarted }
            flow.authorize()
            flow.actions.respond(*answers.toTypedArray())
            assertTrue(case, flow.store.fail)
            assertEquals(case, 1, flow.actions.operations.count { it == "SUBMIT_WALLET_PAYMENT" })
            assertEquals(case, listOf(case != "not started"), flow.outcomes)
            flow.sheetFinished?.invoke()
            assertEquals(case, if (error == null) 1 else 0, flow.submitted)
            assertEquals(case, listOfNotNull(error), flow.errors.map { it.code })
            assertTrue(case, flow.errors.all { it.attemptMayExist })
            assertEquals(case, 0, flow.hosted.size)
            assertEquals(case, 1, flow.actions.finished)
        }
    }

    @Test fun storage_failing_after_the_verification_claim_keeps_the_server_answer() {
        for (answer in listOf("SUBMITTED", "VERIFICATION_REQUIRED")) {
            val submitted = answer == "SUBMITTED"
            val flow = Harness(gated = true)
            flow.session.start()
            flow.actions.respond(response("VERIFICATION_REQUIRED"))
            assertEquals(answer, true, flow.open())
            flow.store.fail = true
            flow.closeVerification?.invoke(true)
            flow.actions.respond(response(answer))
            assertEquals(answer, if (submitted) 1 else 0, flow.submitted)
            assertEquals(answer, if (submitted) emptyList() else listOf("PAYMENT_CONTINUATION_UNAVAILABLE"), flow.errors.map { it.code })
            assertEquals(answer, 1, flow.hosted.size)
            assertEquals(answer, 1, flow.actions.finished)
        }
    }

    @Test fun a_failed_submission_claim_sends_nothing_and_only_an_earlier_claim_may_have_an_attempt() {
        for (earlierClaim in listOf(false, true)) {
            val flow = Harness()
            flow.session.start()
            flow.actions.respond(response("NOT_STARTED"))
            if (earlierClaim) flow.store.value = flow.store.value.copy(submissionStarted = true) else flow.store.fail = true
            flow.authorize()
            flow.actions.runJobs()
            assertEquals("$earlierClaim", listOf(false), flow.outcomes)
            assertEquals("$earlierClaim", listOf("READ_SUBMISSION"), flow.actions.operations)
            assertEquals(
                "$earlierClaim",
                listOf(if (earlierClaim) "PAYMENT_OUTCOME_UNKNOWN" else "PRESENTATION_FAILED"),
                flow.errors.map { it.code },
            )
            assertEquals(
                "$earlierClaim",
                if (earlierClaim) "submission_already_claimed" else "PAYMENT_NOT_SUBMITTED",
                flow.errors.single().detail,
            )
            assertEquals("$earlierClaim", earlierClaim, flow.errors.single().attemptMayExist)
            assertEquals("$earlierClaim", "test-order", flow.errors.single().orderId)
            assertFalse("$earlierClaim", flow.errors.single().recoverable)
            assertEquals("$earlierClaim", 1, flow.actions.finished)
        }
    }

    @Test fun invalid_wallet_data_is_rejected_before_claim_or_network_mutation() {
        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        val invalid = WalletPayment("", "Test", "Customer", "test@example.com", WalletFixtures.payment.billing)
        flow.submitWallet?.invoke(invalid) { flow.outcomes += it }
        assertEquals(listOf(false), flow.outcomes)
        assertFalse(flow.store.value.submissionStarted)
        assertEquals(0, flow.actions.queued)
        assertEquals(listOf("READ_SUBMISSION"), flow.actions.operations)
        assertEquals(listOf("PRESENTATION_FAILED"), flow.errors.map { it.code })
        assertEquals("PAYMENT_NOT_SUBMITTED", flow.errors.single().detail)
        assertFalse(flow.errors.single().attemptMayExist)
    }

    @Test fun a_sheet_that_finishes_inside_a_rejected_completion_still_reports_the_failure() {
        val dismissed = Harness(gated = true)
        dismissed.session.start()
        dismissed.actions.respond(response("NOT_STARTED"))
        dismissed.sheetFinished?.invoke()
        assertEquals(1, dismissed.actions.finished)
        assertTrue(dismissed.errors.isEmpty())

        for (claimFails in listOf(false, true)) {
            val flow = Harness(gated = true)
            flow.session.start()
            flow.actions.respond(response("NOT_STARTED"))
            flow.store.fail = claimFails
            val payment = if (claimFails) {
                WalletFixtures.payment
            } else {
                WalletPayment("", "Test", "Customer", "test@example.com", WalletFixtures.payment.billing)
            }
            flow.submitWallet?.invoke(payment) { accepted ->
                flow.outcomes += accepted
                flow.sheetFinished?.invoke()
            }
            flow.actions.runJobs()
            assertEquals("$claimFails", listOf(false), flow.outcomes)
            assertEquals("$claimFails", listOf("PRESENTATION_FAILED"), flow.errors.map { it.code })
            assertEquals("$claimFails", "PAYMENT_NOT_SUBMITTED", flow.errors.single().detail)
            assertEquals("$claimFails", 1, flow.actions.finished)
        }
    }

    @Test fun reentrant_unmount_from_status_prevents_further_callbacks_or_surfaces() {
        for (state in listOf("SUBMITTED", "VERIFICATION_REQUIRED", "UNKNOWN")) {
            val flow = Harness()
            flow.onStatus = { flow.session.unmount() }
            flow.session.start()
            flow.actions.respond(response(state))
            assertEquals(state, 0, flow.submitted)
            assertEquals(state, 0, flow.hosted.size)
            assertEquals(state, 0, flow.errors.size)
        }
    }

    @Test fun untrusted_verification_url_fails_without_opening_a_surface() {
        val flow = Harness()
        flow.session.start()
        @Suppress("UNCHECKED_CAST")
        val verification = response("VERIFICATION_REQUIRED")["verification"] as Map<String, Any?>
        flow.actions.respond(response("VERIFICATION_REQUIRED") +
            ("verification" to verification + ("url" to "https://attacker.example/payment?secret=synthetic")))
        assertEquals(0, flow.hosted.size)
        assertEquals(listOf("INVALID_VERIFICATION"), flow.errors.map { it.code })
        assertNull(flow.errors.single().detail)
        assertFalse(flow.errors.single().message.contains("synthetic"))
    }

    @Test fun gated_unopened_verification_ends_with_exactly_one_cancel() {
        val flow = Harness(gated = true)
        flow.session.start()
        flow.actions.respond(response("VERIFICATION_REQUIRED"))
        flow.closeVerification?.invoke(false)
        flow.closeVerification?.invoke(false)
        assertEquals(1, flow.cancelled)
        assertEquals(listOf(MeldStatus.PENDING), flow.statuses.map { it.status })
        assertTrue(flow.errors.isEmpty())
        assertEquals(0, flow.submitted)
    }

    @Test fun gated_submitted_ends_with_exactly_one_submission() {
        val flow = Harness(gated = true)
        flow.session.start()
        flow.actions.respond(response("SUBMITTED"))
        flow.session.unmount()
        assertEquals(1, flow.submitted)
        assertEquals(0, flow.cancelled)
        assertTrue(flow.errors.isEmpty())
    }

    @Test fun a_wallet_that_cannot_pay_before_any_attempt_is_reported_as_unavailable() {
        val flow = Harness(gated = true)
        flow.sheetError = WalletUnavailableException("Google Pay has no usable card on this device.")
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        assertEquals(listOf("PAYMENT_METHOD_UNAVAILABLE"), flow.errors.map { it.code })
        assertEquals("Google Pay has no usable card on this device.", flow.errors.single().message)
        assertFalse(flow.errors.single().recoverable)
        assertFalse(flow.errors.single().attemptMayExist)
        assertFalse(flow.store.value.submissionStarted)
        assertEquals(1, flow.actions.finished)

        val other = Harness(gated = true)
        other.sheetError = IllegalStateException("synthetic")
        other.session.start()
        other.actions.respond(response("NOT_STARTED"))
        assertEquals(listOf("PRESENTATION_FAILED"), other.errors.map { it.code })
        assertEquals("PAYMENT_CONTINUATION_UNAVAILABLE", other.errors.single().detail)
        assertFalse(other.errors.single().attemptMayExist)
        assertFalse(other.store.value.submissionStarted)
    }

    @Test fun a_verification_surface_that_cannot_be_built_may_follow_an_attempt() {
        for (error in listOf(WalletUnavailableException("synthetic"), IllegalStateException("synthetic"))) {
            val flow = Harness(gated = true)
            flow.hostedError = error
            flow.session.start()
            flow.actions.respond(response("VERIFICATION_REQUIRED"))
            assertEquals("$error", 0, flow.hosted.size)
            assertEquals("$error", listOf("PAYMENT_CONTINUATION_UNAVAILABLE"), flow.errors.map { it.code })
            assertNull("$error", flow.errors.single().detail)
            assertTrue("$error", flow.errors.single().attemptMayExist)
            assertEquals("$error", 1, flow.actions.finished)
        }
    }

    // Android: Google Pay hands over its token after its sheet has closed.

    @Test fun the_sheet_may_finish_before_its_submission_resolves() {
        for (answer in listOf("SUBMITTED", "VERIFICATION_REQUIRED")) {
            val flow = Harness(gated = true)
            flow.session.start()
            flow.actions.respond(response("NOT_STARTED"))
            flow.authorize()
            flow.sheetFinished?.invoke()
            assertEquals(answer, 0, flow.actions.finished)
            assertTrue(answer, flow.statuses.isEmpty() && flow.errors.isEmpty())
            flow.actions.respond(response(answer))
            assertEquals(answer, listOf(true), flow.outcomes)
            assertEquals(answer, listOf(MeldStatus.PENDING), flow.statuses.map { it.status })
            assertEquals(answer, if (answer == "SUBMITTED") 1 else 0, flow.submitted)
            assertEquals(answer, if (answer == "SUBMITTED") 0 else 1, flow.hosted.size)
            flow.session.unmount()
        }
    }

    @Test fun a_payment_arriving_after_the_owner_is_gone_is_dropped_and_nothing_is_sent() {
        val flow = Harness()
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        flow.session.unmount()
        flow.authorize()
        assertEquals(listOf(false), flow.outcomes)
        assertEquals(0, flow.actions.queued)
        assertFalse(flow.store.value.submissionStarted)
        assertEquals(listOf("READ_SUBMISSION"), flow.actions.operations)
        assertTrue(flow.surfaces.single().unmounted)
    }

    @Test fun a_host_destroyed_mid_submit_keeps_the_fence_and_the_remount_reports_an_unknown_outcome() {
        val store = MemoryStore()
        val flow = Harness(store = store, gated = true)
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        flow.authorize()
        flow.session.hostGone()
        flow.session.hostGone()
        assertEquals(listOf(false), flow.outcomes)
        assertEquals(listOf("PAYMENT_OUTCOME_UNKNOWN"), flow.errors.map { it.code })
        assertEquals("session_ended", flow.errors.single().detail)

        flow.actions.respond(response("SUBMITTED"))
        assertEquals(listOf("READ_SUBMISSION", "SUBMIT_WALLET_PAYMENT"), flow.actions.operations)
        assertTrue(store.value.submissionStarted)
        assertEquals(0, flow.submitted)
        assertEquals(1, flow.errors.size)

        val remount = Harness(identity = flow.identity, store = store)
        remount.session.start()
        remount.actions.respond(response("NOT_STARTED"))
        assertEquals(0, remount.sheets)
        assertEquals(listOf("PAYMENT_OUTCOME_UNKNOWN"), remount.errors.map { it.code })
    }

    @Test fun a_host_destroyed_before_authorization_reports_that_nothing_was_sent() {
        val flow = Harness(gated = true)
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        flow.session.hostGone()
        flow.session.hostGone()
        assertEquals(listOf("PRESENTATION_FAILED"), flow.errors.map { it.code })
        assertEquals("host_detached", flow.errors.single().detail)
        assertFalse(flow.errors.single().attemptMayExist)
        assertFalse(flow.store.value.submissionStarted)
        assertTrue(flow.surfaces.single().unmounted)
        assertEquals(1, flow.actions.finished)

        flow.authorize()
        assertEquals(listOf(false), flow.outcomes)
        assertEquals(listOf("READ_SUBMISSION"), flow.actions.operations)
    }

    @Test fun an_unreadable_state_is_reported_as_unavailable() {
        for (answer in listOf<Any>(PaymentActionException.Http(503), mapOf("version" to 2, "status" to "SUBMITTED", "nextStep" to "WAIT_FOR_PAYMENT"))) {
            val flow = Harness()
            flow.session.start()
            flow.actions.respond(answer)
            assertEquals(listOf("PAYMENT_STATE_UNAVAILABLE"), flow.errors.map { it.code })
            assertFalse(flow.store.value.submissionStarted)
            assertEquals(0, flow.sheets)
        }
    }

    @Test fun store_and_network_work_never_runs_on_main() {
        val flow = Harness()
        flow.store.guard = { check(flow.actions.inJob) { "store touched on main" } }
        flow.session.start()
        flow.actions.respond(response("NOT_STARTED"))
        flow.authorize()
        flow.actions.respond(response("VERIFICATION_REQUIRED"))
        flow.sheetFinished?.invoke()
        assertEquals(true, flow.open())
        flow.closeVerification?.invoke(true)
        flow.actions.respond(response("SUBMITTED"))
        assertEquals(1, flow.submitted)
        assertEquals(listOf("READ_SUBMISSION", "SUBMIT_WALLET_PAYMENT", "READ_SUBMISSION"), flow.actions.operations)
        assertTrue(flow.actions.requests.all { it.inJob })
    }
}

private class FakeSurface : MeldProviderSession {
    var unmounted = false

    override fun unmount() {
        unmounted = true
    }
}

/** Jobs queue until a test runs them; answers are scripted in order. Like the real queue, nothing is delivered after finish. */
private class FakeActions(private val log: MutableList<String>) : PaymentActionSending {
    class Request(val operation: String, val fields: Map<String, Any?>, val key: UUID?, val inJob: Boolean)

    val requests = mutableListOf<Request>()
    val operations: List<String> get() = requests.map { it.operation }
    private val answers = ArrayDeque<Any>()
    private val jobs = ArrayDeque<() -> Unit>()
    var finished = 0
    var inJob = false
        private set
    val queued: Int get() = jobs.size

    override fun execute(operation: String, fields: Map<String, Any?>, key: UUID?): Map<String, Any?> {
        requests += Request(operation, fields, key, inJob)
        log += (if (operation == "READ_SUBMISSION") "read:" else "send:") + operation
        @Suppress("UNCHECKED_CAST")
        return when (val answer = answers.removeFirstOrNull() ?: error("no answer scripted for $operation")) {
            is Exception -> throw answer
            else -> answer as Map<String, Any?>
        }
    }

    override fun <T> run(job: () -> T, onResult: (Result<T>) -> Unit) {
        if (finished > 0) return
        jobs += {
            inJob = true
            val result = try {
                Result.success(job())
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                inJob = false
            }
            if (finished == 0) onResult(result)
        }
    }

    override fun finish() {
        finished += 1
    }

    /** Scripts the next answers (a map, or an exception to throw), then runs every queued job. */
    fun respond(vararg answer: Any) {
        answers.addAll(answer)
        runJobs()
    }

    fun runJobs() {
        while (true) {
            val job = jobs.removeFirstOrNull() ?: return
            job()
        }
    }
}

private class MemoryStore : AttemptStore {
    var value = AttemptRecord()
    var fail = false
    var guard: () -> Unit = {}
    var log: MutableList<String>? = null

    private fun touch(event: String) {
        guard()
        log?.add("store:$event")
        if (fail) throw PaymentActionException.Storage
    }

    override fun record(): AttemptRecord {
        touch("record")
        return value
    }

    override fun claimSubmission(): UUID {
        touch("claim")
        if (value.submissionStarted) throw PaymentActionException.AlreadyAttempted
        value = value.copy(submissionStarted = true)
        return value.submissionKey
    }

    override fun claimVerification() {
        touch("claimVerification")
        if (value.verificationOpened) throw PaymentActionException.AlreadyAttempted
        value = value.copy(submissionStarted = true, verificationOpened = true)
    }

    override fun observeSubmission() {
        touch("observe")
        value = value.copy(submissionStarted = true)
    }
}

private class Harness(
    val identity: String = UUID.randomUUID().toString(),
    val store: MemoryStore = MemoryStore(),
    gated: Boolean = false,
) {
    val log = mutableListOf<String>()
    val actions = FakeActions(log)
    val main = QueuedMain()
    var sheets = 0
    val hosted = mutableListOf<WalletVerification>()
    val surfaces = mutableListOf<FakeSurface>()
    var submitted = 0
    var cancelled = 0
    val outcomes = mutableListOf<Boolean>()
    val errors = mutableListOf<MeldError>()
    val statuses = mutableListOf<MeldStatusChange>()
    var now = NOW
    var sheetError: Exception? = null
    var hostedError: Exception? = null
    var onStatus: (() -> Unit)? = null
    var submitWallet: ((WalletPayment, (Boolean) -> Unit) -> Unit)? = null
    var sheetFinished: (() -> Unit)? = null
    var openVerification: (((Boolean) -> Unit) -> Unit)? = null
    var closeVerification: ((Boolean) -> Unit)? = null
    val session: WalletPaymentSession

    init {
        store.log = log
        val handlers = MeldEventHandlers(
            onPaymentSubmitted = { submitted += 1 },
            onStatusChange = { statuses += it; onStatus?.invoke() },
            onCancel = { cancelled += 1 },
            onError = { errors += it },
        )
        session = WalletPaymentSession(
            identity = identity,
            orderId = "test-order",
            actions = actions,
            store = store,
            handlers = if (gated) handlers.gated() else handlers,
            sheetFactory = { submit, finished ->
                sheetError?.let { throw it }
                sheets += 1
                submitWallet = submit
                sheetFinished = finished
                FakeSurface().also { surfaces += it }
            },
            hostedFactory = { verification, open, close ->
                hostedError?.let { throw it }
                hosted += verification
                openVerification = open
                closeVerification = close
                FakeSurface().also { surfaces += it }
            },
            acceptsVerification = { isRegisteredWidgetUrl(it.toString(), setOf("https://sandbox-exchange.mrcr.io")) },
            now = { now },
            main = main,
        )
    }

    fun authorize() {
        submitWallet?.invoke(WalletFixtures.payment) { outcomes += it }
    }

    /** Taps Continue, lets the claim run, and returns whether the session said to open the page. */
    fun open(): Boolean? {
        var answer: Boolean? = null
        openVerification?.invoke { answer = it }
        actions.runJobs()
        return answer
    }
}
