package io.meld.sdk.requirements

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.actions.ActionQueue
import io.meld.sdk.actions.DirectExecutor
import io.meld.sdk.actions.QueuedMain
import io.meld.sdk.hosted.VirtualClock
import io.meld.sdk.wallet.parseRfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The loop over the shared fixtures, on a virtual clock with an in-memory server. */
class RequirementsLoopTest {
    private class Run(
        val fixture: SessionFixture,
        handlers: MeldReadyHandlers = MeldReadyHandlers(),
        options: MeldReadyOptions = MeldReadyOptions(),
        val presenter: FakePresenter? = FakePresenter(),
        environment: MeldEnvironment = MeldEnvironment.SANDBOX,
        wallStart: String = fixture.exchange(0)["at"] as String,
    ) {
        val clock = VirtualClock()
        val server = ScriptedServer()
        val keys = ArrayDeque<UUID>()
        val results = mutableListOf<MeldReadyResult>()
        private val wall = checkNotNull(parseRfc3339(wallStart))
        val loop = RequirementsLoop(environment, handlers, options, presenter, { results += it }, clock, { wall + clock.time },
            { keys.removeFirstOrNull() ?: UUID.randomUUID() }) { SessionTransport(it, ActionQueue(DirectExecutor(), clock), server.connector) }

        val result: MeldReadyResult get() = results.single()

        fun start(session: MeldRequirementsSession = fixture.session) {
            loop.start(session)
            clock.advance()
        }
    }

    private val noahStep = "NOAH:EXTERNAL_KYC:NOAH_HOSTED_ONBOARDING"
    private val meldKyc = "*:MELD_KYC:MELD_KYC_APPROVED"
    private val terms = "UPHOLD:AGREEMENT:general-us"
    private val email = "*:CUSTOMER_DETAILS:CUSTOMER_EMAIL"

    @Test fun every_session_fixture_replays_through_the_loop_with_each_handler_answering_as_the_fixture_did() {
        val names = RequirementsFixtures.sessionNames()
        assertTrue(names.size >= 9)
        for (name in names) {
            val fx = SessionFixture(name)
            val server = ReplayServer(fx)
            val clock = VirtualClock()
            val wall = checkNotNull(parseRfc3339(fx.exchange(0)["at"] as String))
            val results = mutableListOf<MeldReadyResult>()
            val handlers = MeldReadyHandlers(
                collect = { request, answer -> answer(server.scriptedFields(request.stepId)) },
                confirmAgreement = { _, answer -> answer(true) },
                presentUrl = { _, closed -> closed() },
            )
            val loop = RequirementsLoop(MeldEnvironment.SANDBOX, handlers, MeldReadyOptions(waitTimeoutMs = 30_000), null,
                { results += it }, clock, { wall + clock.time }) {
                SessionTransport(it, ActionQueue(DirectExecutor(), clock), server.connector)
            }
            server.onUnscripted = loop::cancel
            loop.start(fx.session)
            clock.advance(600_000)

            assertEquals(name, 1, results.size)
            assertNotEquals(name, MeldReadyStatus.FAILED, results.single().status)
            assertEquals("$name reports the providers as last read, with each open step's reason and errorCodes",
                reportedProviders(checkNotNull(server.current)), results.single().providers)
            assertTrue(name, server.unscripted.size <= 1)
            @Suppress("UNCHECKED_CAST")
            val offered = fx.exchanges.filter { it["operation"] == "READ" }
                .flatMap { (it["response"] as Map<String, Any?>)["next"] as? List<Map<String, Any?>> ?: emptyList() }
                .map { it["stepId"] }.toSet()
            assertEquals(name, fx.exchanges.filter { it["operation"] == "RUN_STEP" && (it["request"] as Map<*, *>)["stepId"] !in offered },
                server.unused)
            for (index in server.sent.indices) {
                assertEquals(name, fx.endpoint, server.sent[index].url.toString())
                assertEquals(name, fx.bearer, server.sent[index].headers["Authorization"])
                if (server.body(index)["operation"] == "READ") {
                    assertEquals(name, mapOf("version" to 1, "operation" to "READ"), server.body(index))
                    assertNull(name, server.sent[index].headers["X-Idempotency-Key"])
                }
            }
            val runs = server.sent.indices.filter { server.body(it)["operation"] == "RUN_STEP" }
            val keys = runs.map { UUID.fromString(server.sent[it].headers["X-Idempotency-Key"]) }
            for (i in 1 until runs.size) {
                if (server.statuses[runs[i - 1]] == 425) {
                    assertEquals("$name retries a 425 with its key", keys[i - 1], keys[i])
                } else {
                    assertFalse("$name sends each step with a new key", keys[i] in keys.take(i))
                }
            }
        }
    }

    @Test fun a_share_the_provider_refused_ends_blocked_with_its_reason_and_error_codes_in_open() {
        val fx = SessionFixture("coinme-share-refused")
        val run = Run(fx, MeldReadyHandlers(collect = { _, _ -> throw AssertionError("nothing to collect") }))
        run.server.answer(fx.exchange(0))
        run.start()

        assertEquals(MeldReadyStatus.BLOCKED, run.result.status)
        assertEquals(1, run.server.sent.size)
        assertNull(run.result.pending)
        val provider = run.result.providers.single()
        assertEquals("COINME", provider.serviceProvider)
        assertEquals(false, provider.eligible)
        assertEquals(
            listOf(
                MeldReadyOpenStep("PROVIDER_KYC_SHARE", "PROVIDER_KYC_SHARE", "BLOCKED", "Coinme needs the customer's phone verified first",
                    listOf("PHONE_VERIFICATION_REQUIRED")),
                MeldReadyOpenStep("PROVIDER_REVIEW", "PROVIDER_KYC_APPROVED", "REQUIRED"),
            ),
            provider.open,
        )
    }

    @Test fun banxa_fresh_submits_meld_kyc_presents_the_sumsub_link_and_waits_out_the_review() {
        val fx = SessionFixture("banxa-fresh")
        val waiting = SessionFixture("waiting-on-meld-kyc")
        val states = mutableListOf<MeldReadyState>()
        val run = Run(fx, MeldReadyHandlers(onProgress = { states += it.state }), MeldReadyOptions(waitTimeoutMs = 20_000))
        run.keys += fx.key(1)
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.start()

        run.server.assertSent(0, fx, fx.exchange(0))
        run.server.assertSent(1, fx, fx.exchange(1))
        assertEquals(listOf("https://in.sumsub.com/websdk/p/sbx_fixture_1"), run.presenter!!.presented)
        assertTrue(run.results.isEmpty())

        repeat(3) { run.server.answer(waiting.exchange(0)) }
        run.presenter.close()
        run.clock.advance()
        assertEquals("no refresh after a submit's page", 3, run.server.sent.size)
        assertEquals(mapOf("version" to 1, "operation" to "READ"), run.server.body(2))
        run.clock.advance(14_999)
        assertEquals(3, run.server.sent.size)
        run.clock.advance(1)
        assertEquals(4, run.server.sent.size)
        assertTrue(run.results.isEmpty())
        run.clock.advance(15_000)

        assertEquals(MeldReadyStatus.PENDING, run.result.status)
        assertEquals(listOf(MeldReadyProvider("BANXA", "WAITING", false, listOf(
            MeldReadyOpenStep("MELD_KYC", "MELD_KYC_APPROVED", "PENDING"),
            MeldReadyOpenStep("PROVIDER_KYC_SHARE", "PROVIDER_KYC_SHARE", "REQUIRED"),
            MeldReadyOpenStep("PROVIDER_REVIEW", "PROVIDER_KYC_APPROVED", "REQUIRED"),
        ))), run.result.providers)
        assertNull(run.result.pending)
        assertEquals(emptyList<MeldReadyContinuation>(), run.result.continuation)
        assertEquals(listOf(MeldReadyState.READING, MeldReadyState.RUNNING, MeldReadyState.PRESENTING, MeldReadyState.READING,
            MeldReadyState.WAITING, MeldReadyState.READING, MeldReadyState.WAITING, MeldReadyState.READING), states)
        assertEquals(0, run.server.pending)
    }

    @Test fun uphold_fresh_asks_for_the_terms_then_the_email_again_after_field_errors_then_submits() {
        val fx = SessionFixture("uphold-fresh")
        val after = SessionFixture("uphold-after-terms-and-details")
        val agreements = mutableListOf<MeldAgreementRequest>()
        val collects = mutableListOf<MeldCollectRequest>()
        val answers = ArrayDeque(listOf("not-an-email", "ada@example.com"))
        val run = Run(fx, MeldReadyHandlers(
            confirmAgreement = { request, done -> agreements += request; done(true) },
            collect = { request, done -> collects += request; done(mapOf("email" to answers.removeFirst())) },
        ))
        run.keys += listOf(fx.key(2), fx.key(1), fx.key(3), after.key(1))
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(2))
        run.server.answer(200, fx.response(0).withNext(email, meldKyc))
        run.server.answer(fx.exchange(1))
        run.server.answer(fx.exchange(3))
        run.server.answer(after.exchange(0))
        run.server.answer(after.exchange(1))
        run.start()

        assertEquals(listOf(MeldAgreementRequest(terms, "UPHOLD", "general-us", "https://uphold.test/tos-us")), agreements)
        run.server.assertSent(1, fx, fx.exchange(2))
        run.server.assertSent(3, fx, fx.exchange(1))
        run.server.assertSent(4, fx, fx.exchange(3))
        run.server.assertSent(6, fx, after.exchange(1))
        assertEquals(listOf(
            MeldCollectRequest(email, "CUSTOMER_DETAILS", "CUSTOMER_EMAIL", listOf("UPHOLD"), listOf(MeldCollectField("email"))),
            MeldCollectRequest(email, "CUSTOMER_DETAILS", "CUSTOMER_EMAIL", listOf("UPHOLD"), listOf(MeldCollectField("email")),
                fieldErrors = listOf(MeldFieldError("email", "INVALID"))),
        ), collects)
        assertEquals(listOf("https://in.sumsub.com/websdk/p/sbx_fixture_1"), run.presenter!!.presented)

        run.loop.cancel()
        assertEquals(MeldReadyStatus.CANCELLED, run.result.status)
        assertEquals(1, run.presenter.dismissed)
        assertEquals(listOf("UPHOLD"), run.result.providers.map { it.serviceProvider })
    }

    @Test fun banxa_and_uphold_share_one_meld_kyc_and_finish_ready() {
        val fx = SessionFixture("banxa-uphold")
        val run = Run(fx, MeldReadyHandlers(
            confirmAgreement = { _, done -> done(true) },
            collect = { _, done -> done(mapOf("email" to "ada@example.com")) },
        ))
        run.keys += listOf(fx.key(1), fx.key(2), fx.key(4))
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.server.answer(200, fx.response(0).withNext(email, meldKyc))
        run.server.answer(fx.exchange(2))
        run.server.answer(fx.exchange(3))
        run.server.answer(fx.exchange(4))
        run.start()
        run.server.assertSent(1, fx, fx.exchange(1))
        assertEquals(mapOf("version" to 1, "operation" to "READ"), run.server.body(2))
        run.server.assertSent(3, fx, fx.exchange(2))
        run.server.assertSent(4, fx, fx.exchange(3))
        run.server.assertSent(5, fx, fx.exchange(4))
        assertEquals(listOf("BANXA", "UPHOLD"), (fx.exchange(4)["upstream"] as List<*>).let { upstream ->
            ((upstream.single() as Map<*, *>)["body"] as Map<*, *>)["targetServiceProviders"]
        })

        val ready = fx.response(3) + ("status" to "READY") + ("next" to emptyList<Any>()) +
            ("providers" to listOf(provider("BANXA", "READY", true), provider("UPHOLD", "READY", true)))
        run.server.answer(200, ready)
        run.presenter!!.close()
        run.clock.advance()

        assertEquals(MeldReadyStatus.READY, run.result.status)
        assertEquals(listOf(MeldReadyProvider("BANXA", "READY", true, emptyList()), MeldReadyProvider("UPHOLD", "READY", true, emptyList())),
            run.result.providers)
        assertEquals(7, run.server.sent.size)
    }

    @Test fun noah_refreshes_a_link_about_to_expire_before_it_shows_and_once_after_it_closes() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val run = Run(fx)
        run.keys += listOf(fx.key(1), fx.key(2))
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.start()

        run.server.assertSent(1, fx, fx.exchange(1))
        assertEquals("the refreshed link, never the stale one", listOf("https://onboarding.noah.test/hosted?session=fixture-2"),
            run.presenter!!.presented)

        run.server.answer(fx.exchange(2))
        run.server.answer(fx.exchange(3))
        run.clock.advance(355_000)
        run.presenter.close()
        run.clock.advance()
        run.server.assertSent(2, fx, fx.exchange(2))
        assertEquals(4, run.server.sent.size)
        assertTrue(run.results.isEmpty())
        run.loop.cancel()
        assertEquals(listOf(MeldReadyProvider("NOAH", "WAITING", false, listOf(MeldReadyOpenStep("PROVIDER_REVIEW", "PROVIDER_KYC_APPROVED", "PENDING")))),
            run.result.providers)
    }

    @Test fun a_link_with_a_minute_or_more_left_shows_at_once() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val run = Run(fx, wallStart = "2026-11-04T14:59:40Z")
        run.server.answer(fx.exchange(0))
        run.start()
        assertEquals(listOf("https://onboarding.noah.test/hosted?session=fixture-1"), run.presenter!!.presented)
        assertEquals(1, run.server.sent.size)
    }

    @Test fun a_link_without_an_expiry_shows_as_it_is_and_one_without_a_url_is_refreshed_first() {
        val fx = SessionFixture("noah-hosted-onboarding")
        @Suppress("UNCHECKED_CAST")
        fun withStep(change: (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> {
            val read = fx.response(0)
            return read + ("next" to (read["next"] as List<Map<String, Any?>>).map(change))
        }
        val noExpiry = Run(fx)
        noExpiry.server.answer(200, withStep { it - "expiresAt" })
        noExpiry.start()
        assertEquals(listOf("https://onboarding.noah.test/hosted?session=fixture-1"), noExpiry.presenter!!.presented)

        val noUrl = Run(fx)
        noUrl.server.answer(200, withStep { it - "url" - "expiresAt" })
        noUrl.server.answer(fx.exchange(1))
        noUrl.start()
        assertEquals(mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to noahStep, "refresh" to true), noUrl.server.body(1))
        assertEquals(listOf("https://onboarding.noah.test/hosted?session=fixture-2"), noUrl.presenter!!.presented)
    }

    @Test fun a_page_reached_while_the_activity_is_away_opens_from_a_fresh_read_once_it_resumes() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val run = Run(fx, presenter = FakePresenter(resumed = false), wallStart = "2026-11-04T14:59:40Z")
        run.server.answer(fx.exchange(0))
        run.start()
        run.clock.advance(600_000)
        assertTrue("nothing opens while the activity is away", run.presenter!!.presented.isEmpty())
        assertTrue(run.presenter.waitingForResume)
        assertEquals(1, run.server.sent.size)
        assertTrue(run.results.isEmpty())

        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.presenter.resume()
        run.clock.advance()
        assertEquals("READ", run.server.body(1)["operation"])
        assertEquals("the link aged while away, so it is refreshed first",
            mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to noahStep, "refresh" to true), run.server.body(2))
        assertEquals(listOf("https://onboarding.noah.test/hosted?session=fixture-2"), run.presenter.presented)

        val away = Run(fx, presenter = FakePresenter(resumed = false), wallStart = "2026-11-04T14:59:40Z")
        away.server.answer(fx.exchange(0))
        away.start()
        away.loop.cancel()
        assertEquals(MeldReadyStatus.CANCELLED, away.result.status)
        assertFalse(away.presenter!!.waitingForResume)
        away.presenter.resume()
        assertEquals(1, away.server.sent.size)
    }

    @Test fun a_page_the_customer_closed_unfinished_is_not_shown_again_and_reports_the_step_after_the_settle_window() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val run = Run(fx)
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.start()
        run.server.answer(200, mapOf("version" to 1, "stepId" to noahStep, "outcome" to "OPEN_URL",
            "url" to "https://onboarding.noah.test/hosted?session=fixture-3", "expiresAt" to "2026-11-04T16:00:06Z"))
        @Suppress("UNCHECKED_CAST")
        val remint = fx.response(0).let { read ->
            read + ("expiresAt" to "2026-11-04T15:28:31Z") + ("next" to (read["next"] as List<Map<String, Any?>>).map {
                it + ("url" to "https://onboarding.noah.test/hosted?session=fixture-3") + ("expiresAt" to "2026-11-04T16:00:06Z")
            })
        }
        repeat(9) { run.server.answer(200, remint) }
        run.presenter!!.close()
        run.clock.advance(28_000)
        assertTrue(run.results.isEmpty())
        run.clock.advance(4_000)

        assertEquals("shown once, though every read carries a new link", 1, run.presenter.presented.size)
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, run.result.status)
        assertEquals(listOf(noahStep), run.result.pending!!.map { it.stepId })
        assertEquals(0, run.server.pending)
        assertEquals("one refresh before, one after", 2, (0 until run.server.sent.size).count { run.server.body(it)["operation"] == "RUN_STEP" })
    }

    @Test fun a_conflict_on_the_refresh_after_a_page_closed_reads_and_continues() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val run = Run(fx, wallStart = "2026-11-04T14:59:40Z")
        run.server.answer(fx.exchange(0))
        run.start()
        run.server.answer(409, mapOf("version" to 1, "code" to "CONCURRENT_STATE_CHANGE"))
        run.server.answer(200, fx.response(0) + ("status" to "READY") + ("next" to emptyList<Any>()) +
            ("providers" to listOf(provider("NOAH", "READY", true))))
        run.presenter!!.close()
        run.clock.advance()

        assertEquals(mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to noahStep, "refresh" to true), run.server.body(1))
        assertEquals("READ", run.server.body(2)["operation"])
        assertEquals(MeldReadyStatus.READY, run.result.status)
        assertNull(run.result.error)
    }

    @Test fun a_page_closed_on_a_lapsed_session_is_refreshed_on_the_renewed_one_and_not_shown_again() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val unauthorized = mapOf("version" to 1, "code" to "AUTHORIZATION_REQUIRED")
        val unknown = mapOf("version" to 1, "code" to "OUTCOME_UNKNOWN")
        var renewals = 0
        fun closeUnfinished(refusals: List<Pair<Int, Map<String, Any?>>>): Run {
            renewals = 0
            val run = Run(fx, MeldReadyHandlers(renewSession = { done ->
                renewals += 1
                done(MeldRequirementsSession.fromMap(fx.mint + ("token" to "synthetic-renewed")))
            }))
            run.server.answer(fx.exchange(0))
            run.server.answer(fx.exchange(1))
            run.start()
            refusals.forEach { (status, body) -> run.server.answer(status, body) }
            run.server.answer(fx.exchange(2))
            repeat(9) { run.server.answer(fx.exchange(0)) }
            run.presenter!!.close()
            run.clock.advance(32_000)
            return run
        }
        val closed = closeUnfinished(emptyList())
        assertEquals(0, renewals)
        assertEquals(1, closed.presenter!!.presented.size)
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, closed.result.status)
        assertEquals(listOf(noahStep), closed.result.pending!!.map { it.stepId })
        assertEquals(0, closed.server.pending)

        for ((refusals, runSteps) in listOf(
            listOf(401 to unauthorized) to listOf(1, 2, 3),
            listOf(503 to unknown, 401 to unauthorized) to listOf(1, 2, 4),
        )) {
            val run = closeUnfinished(refusals)
            val resent = runSteps.last()
            assertEquals(1, renewals)
            assertEquals("shown once", closed.presenter.presented, run.presenter!!.presented)
            assertEquals(runSteps, run.server.sent.indices.filter { run.server.body(it)["operation"] == "RUN_STEP" })
            assertEquals(fx.bearer, run.server.sent[2].headers["Authorization"])
            assertEquals(fx.exchange(2)["request"], run.server.body(resent))
            assertEquals("Bearer synthetic-renewed", run.server.sent[resent].headers["Authorization"])
            assertNotEquals(run.server.key(2), run.server.key(resent))
            assertEquals("as after any close", closed.result, run.result)
            assertEquals(0, run.server.pending)
        }
    }

    @Test fun a_second_401_on_the_refresh_after_a_page_closed_expires() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val unauthorized = mapOf("version" to 1, "code" to "AUTHORIZATION_REQUIRED")
        var renewals = 0
        val run = Run(fx, MeldReadyHandlers(renewSession = { done ->
            renewals += 1
            done(MeldRequirementsSession.fromMap(fx.mint + ("token" to "synthetic-renewed")))
        }))
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.start()
        run.server.answer(401, unauthorized)
        run.server.answer(401, unauthorized)
        run.presenter!!.close()
        run.clock.advance()

        assertEquals(MeldReadyStatus.EXPIRED, run.result.status)
        assertNull(run.result.error)
        assertEquals(1, renewals)
        assertEquals(listOf("READ", "RUN_STEP", "RUN_STEP", "RUN_STEP"), run.server.sent.indices.map { run.server.body(it)["operation"] })
        assertEquals(fx.exchange(2)["request"], run.server.body(3))
        assertEquals("Bearer synthetic-renewed", run.server.sent[3].headers["Authorization"])
        assertEquals(1, run.presenter.presented.size)
    }

    @Test fun the_plan_fingerprint_ignores_links_and_expiries_at_any_depth_and_only_the_top_level_poll_interval() {
        val read = SessionFixture("noah-hosted-onboarding").response(0)
        fun fingerprint(raw: Map<String, Any?>) = RequirementsSnapshot.parse(raw)!!.fingerprint

        @Suppress("UNCHECKED_CAST")
        fun relinked(value: Any?): Any? = when (value) {
            is Map<*, *> -> (value as Map<String, Any?>).mapValues { (key, entry) ->
                when (key) {
                    "url" -> "https://onboarding.noah.test/hosted?session=fixture-9"
                    "expiresAt" -> "2026-11-04T16:00:09Z"
                    else -> relinked(entry)
                }
            }
            is List<*> -> value.map(::relinked)
            else -> value
        }
        @Suppress("UNCHECKED_CAST")
        val fresh = relinked(read) as Map<String, Any?> + ("pollAfterMs" to 15_000)
        assertNotEquals(read, fresh)
        assertEquals(fingerprint(read), fingerprint(fresh))

        @Suppress("UNCHECKED_CAST")
        val nested = read + ("providers" to (read["providers"] as List<Map<String, Any?>>).map { it + ("pollAfterMs" to 2_000) })
        assertNotEquals(fingerprint(read), fingerprint(nested))
    }

    @Test fun coinbase_opens_the_hosted_form_and_polls_the_pending_process_every_2_seconds() {
        val fx = SessionFixture("coinbase-limit-upgrade")
        val run = Run(fx)
        run.keys += fx.key(1)
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.start()
        run.server.assertSent(1, fx, fx.exchange(1))
        assertEquals(listOf("https://pay.coinbase.test/limits/upgrade?session=fixture"), run.presenter!!.presented)

        repeat(2) { run.server.answer(fx.exchange(2)) }
        run.presenter.close()
        run.clock.advance(1_999)
        assertEquals(3, run.server.sent.size)
        run.clock.advance(1)
        assertEquals(4, run.server.sent.size)
        run.loop.cancel()
        assertEquals(MeldReadyStatus.CANCELLED, run.result.status)
    }

    @Test fun refresh_wakes_a_waiting_loop_at_once() {
        val fx = SessionFixture("waiting-on-meld-kyc")
        val run = Run(fx)
        run.server.answer(fx.exchange(0))
        run.start()
        run.clock.advance(5_000)
        assertEquals(1, run.server.sent.size)

        run.server.answer(200, fx.response(0) + ("status" to "READY") + ("waitingOn" to emptyList<Any>()) +
            ("providers" to listOf(provider("BANXA", "READY", true))))
        run.loop.refresh()
        run.clock.advance()
        assertEquals(2, run.server.sent.size)
        assertEquals(MeldReadyStatus.READY, run.result.status)
        run.loop.refresh()
        run.clock.advance(60_000)
        assertEquals(2, run.server.sent.size)
    }

    @Test fun nothing_runnable_and_nothing_to_wait_for_is_action_required_at_once_with_no_pending_steps() {
        val fx = SessionFixture("unknown-step")
        val providerFields = mapOf("type" to "PROVIDER_EXTRA_FIELDS", "code" to "PROVIDER_EXTRA_KYC", "status" to "REQUIRED",
            "timing" to "PRE_ORDER")
        val read = fx.response(0) + ("status" to "ACTION_REQUIRED") +
            ("providers" to listOf(provider("BANXA", "ACTION_REQUIRED", false, listOf(providerFields))))
        val run = Run(fx)
        run.server.answer(200, read)
        run.start()
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, run.result.status)
        assertEquals(emptyList<MeldReadyStep>(), run.result.pending)
        assertEquals(listOf(MeldReadyOpenStep("PROVIDER_EXTRA_FIELDS", "PROVIDER_EXTRA_KYC", "REQUIRED")), run.result.providers.single().open)
        assertEquals(1, run.server.sent.size)

        val waiting = Run(fx)
        repeat(2) { waiting.server.answer(200, read + ("waitingOn" to SessionFixture("waiting-on-meld-kyc").response(0)["waitingOn"])) }
        waiting.start()
        waiting.clock.advance(4_000)
        assertEquals("still polls while something is waited on", 2, waiting.server.sent.size)
        assertTrue(waiting.results.isEmpty())
        waiting.loop.cancel()
    }

    @Test fun poll_after_ms_is_clamped_to_1_to_60_seconds_and_is_4_seconds_when_it_is_not_a_number() {
        val fx = SessionFixture("waiting-on-meld-kyc")
        val cases = listOf<Pair<Any?, Long>>(10 to 1_000, 120_000 to 60_000, 1_000_000_000_000L to 60_000, 2_500.5 to 2_500,
            "15000" to 4_000, null to 4_000)
        for ((pollAfterMs, delay) in cases) {
            val read = if (pollAfterMs == null) fx.response(0) - "pollAfterMs" else fx.response(0) + ("pollAfterMs" to pollAfterMs)
            val run = Run(fx)
            repeat(2) { run.server.answer(200, read) }
            run.start()
            run.clock.advance(delay - 1)
            assertEquals("$pollAfterMs", 1, run.server.sent.size)
            run.clock.advance(1)
            assertEquals("$pollAfterMs", 2, run.server.sent.size)
            run.loop.cancel()
        }
    }

    @Test fun undetermined_and_blocked_resolve_at_once_with_the_open_steps() {
        val fx = SessionFixture("unknown-step")
        val run = Run(fx)
        run.server.answer(fx.exchange(0))
        run.start()
        assertEquals(MeldReadyStatus.UNDETERMINED, run.result.status)
        assertEquals(listOf(MeldReadyProvider("BANXA", "UNDETERMINED", null,
            listOf(MeldReadyOpenStep("PROVIDER_EXTRA_FIELDS", "PROVIDER_EXTRA_KYC", "UNKNOWN", "STATE_UNAVAILABLE")))), run.result.providers)

        val blocked = Run(fx)
        blocked.server.answer(200, fx.response(0) + ("status" to "BLOCKED"))
        blocked.start()
        assertEquals(MeldReadyStatus.BLOCKED, blocked.result.status)
    }

    @Test fun a_conflict_reads_again_and_never_resends_the_step() {
        val fresh = SessionFixture("banxa-fresh")
        val waiting = SessionFixture("waiting-on-meld-kyc")
        val run = Run(fresh)
        run.keys += waiting.key(1)
        run.server.answer(fresh.exchange(0))
        run.server.answer(waiting.exchange(1))
        run.server.answer(waiting.exchange(0))
        run.start()
        assertEquals(waiting.exchange(1)["request"], run.server.body(1))
        assertEquals(waiting.key(1).toString(), run.server.key(1))
        assertEquals(mapOf("version" to 1, "operation" to "READ"), run.server.body(2))
        assertTrue(run.results.isEmpty())
        assertTrue(run.presenter!!.presented.isEmpty())
    }

    @Test fun the_sdk_never_accepts_terms_on_its_own() {
        val fx = SessionFixture("uphold-fresh")
        val onlyTerms = fx.response(0).withNext(terms)

        val declined = Run(fx, MeldReadyHandlers(confirmAgreement = { _, done -> done(false) }))
        declined.server.answer(200, onlyTerms)
        declined.start()
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, declined.result.status)
        assertEquals(listOf(terms), declined.result.pending!!.map { it.stepId })
        assertEquals(1, declined.server.sent.size)

        val unasked = Run(fx)
        unasked.server.answer(200, onlyTerms)
        unasked.start()
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, unasked.result.status)
        assertEquals(1, unasked.server.sent.size)
    }

    @Test fun without_handlers_only_submit_runs_and_unknown_kinds_never_run() {
        val fx = SessionFixture("uphold-fresh")
        val run = Run(fx)
        run.server.answer(fx.exchange(0))
        run.server.answer(200, mapOf("version" to 1, "stepId" to meldKyc, "outcome" to "DONE"))
        run.server.answer(200, fx.response(0).withNext(terms, email))
        run.start()
        assertEquals(meldKyc, run.server.body(1)["stepId"])
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, run.result.status)
        assertEquals(listOf(terms, email), run.result.pending!!.map { it.stepId })

        val future = mapOf("stepId" to "UPHOLD:FUTURE:F", "kind" to "FUTURE_KIND", "type" to "FUTURE", "code" to "F",
            "serviceProviders" to listOf("UPHOLD"))
        val unknown = Run(fx, MeldReadyHandlers(collect = { _, _ -> error("never asked") }))
        unknown.server.answer(200, fx.response(0) + ("next" to listOf(future)))
        unknown.start()
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, unknown.result.status)
        assertEquals("FUTURE_KIND", unknown.result.pending!!.single().kind)
        assertEquals(1, unknown.server.sent.size)
    }

    @Test fun a_null_answer_means_the_integrator_handled_the_step() {
        val fx = SessionFixture("uphold-fresh")
        val run = Run(fx, MeldReadyHandlers(collect = { _, done -> done(null) }))
        run.server.answer(200, fx.response(0).withNext(email))
        run.server.answer(SessionFixture("uphold-after-terms-and-details").exchange(0))
        run.server.answer(200, mapOf("version" to 1, "stepId" to meldKyc, "outcome" to "DONE"))
        run.server.answer(SessionFixture("unknown-step").exchange(0))
        run.start()
        assertEquals(listOf("READ", "READ", "RUN_STEP", "READ"), (0..3).map { run.server.body(it)["operation"] })
        assertEquals(MeldReadyStatus.UNDETERMINED, run.result.status)
    }

    @Test fun field_errors_ask_again_at_most_three_times() {
        val fx = SessionFixture("uphold-fresh")
        var asked = 0
        val run = Run(fx, MeldReadyHandlers(collect = { _, done -> asked += 1; done(mapOf("email" to "not-an-email")) }))
        run.server.answer(200, fx.response(0).withNext(email))
        repeat(4) { run.server.answer(fx.exchange(1)) }
        run.start()
        assertEquals(4, asked)
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, run.result.status)
        assertEquals(listOf(email), run.result.pending!!.map { it.stepId })
        assertEquals(4, (1..4).map { run.server.key(it) }.toSet().size)
    }

    @Test fun verify_contact_collects_the_contact_then_the_code_and_sends_the_continuation() {
        val fx = SessionFixture("uphold-fresh")
        val stepId = "*:VERIFICATION:EMAIL"
        val step = mapOf("stepId" to stepId, "kind" to "VERIFY_CONTACT", "type" to "VERIFICATION", "code" to "EMAIL",
            "serviceProviders" to listOf("UPHOLD"))
        val asked = mutableListOf<List<String>>()
        val run = Run(fx, MeldReadyHandlers(collect = { request, done ->
            asked += request.fields.map { it.key }
            done(if (request.fields.single().key == "email") mapOf("email" to "ada@example.com") else mapOf("otp" to "123456"))
        }))
        run.server.answer(200, fx.response(0) + ("next" to listOf(step)))
        run.server.answer(200, mapOf("version" to 1, "stepId" to stepId, "outcome" to "COLLECT", "fields" to listOf(mapOf("key" to "email"))))
        run.server.answer(200, mapOf("version" to 1, "stepId" to stepId, "outcome" to "COLLECT", "fields" to listOf(mapOf("key" to "otp")),
            "continuation" to "ver-1"))
        run.server.answer(200, mapOf("version" to 1, "stepId" to stepId, "outcome" to "DONE"))
        run.server.answer(SessionFixture("unknown-step").exchange(0))
        run.start()

        assertEquals(listOf(listOf("email"), listOf("otp")), asked)
        assertEquals(mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to stepId), run.server.body(1))
        assertEquals(mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to stepId, "fields" to mapOf("email" to "ada@example.com")),
            run.server.body(2))
        assertEquals(mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to stepId, "continuation" to "ver-1",
            "fields" to mapOf("otp" to "123456")), run.server.body(3))
        assertEquals(MeldReadyStatus.UNDETERMINED, run.result.status)
    }

    @Test fun verify_contact_asks_again_for_a_rejected_code_at_most_three_times() {
        val fx = SessionFixture("uphold-fresh")
        val stepId = "*:VERIFICATION:EMAIL"
        val step = mapOf("stepId" to stepId, "kind" to "VERIFY_CONTACT", "type" to "VERIFICATION", "code" to "EMAIL",
            "serviceProviders" to listOf("UPHOLD"))
        val asked = mutableListOf<MeldCollectRequest>()
        val run = Run(fx, MeldReadyHandlers(collect = { request, done -> asked += request; done(mapOf("otp" to "000000")) }))
        run.server.answer(200, fx.response(0) + ("next" to listOf(step)))
        run.server.answer(200, mapOf("version" to 1, "stepId" to stepId, "outcome" to "COLLECT", "fields" to listOf(mapOf("key" to "otp")),
            "continuation" to "ver-1"))
        repeat(4) {
            run.server.answer(400, mapOf("version" to 1, "code" to "INVALID_REQUEST",
                "fieldErrors" to listOf(mapOf("key" to "otp", "reason" to "INVALID"))))
        }
        run.start()

        val rejected = listOf(MeldFieldError("otp", "INVALID"))
        assertEquals(listOf(null, rejected, rejected, rejected), asked.map { it.fieldErrors })
        assertEquals(List(4) { mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to stepId, "continuation" to "ver-1",
            "fields" to mapOf("otp" to "000000")) }, (2..5).map { run.server.body(it) })
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, run.result.status)
        assertEquals(listOf(stepId), run.result.pending!!.map { it.stepId })
        assertEquals(6, run.server.sent.size)
    }

    @Test fun a_transport_failure_is_retried_with_the_same_key_after_1_2_and_4_seconds() {
        val fx = SessionFixture("banxa-fresh")
        val run = Run(fx)
        run.keys += fx.key(1)
        run.server.answer(fx.exchange(0))
        run.server.fail("connect")
        run.server.fail("read")
        run.server.answer(fx.exchange(1))
        run.start()
        assertEquals(2, run.server.sent.size)
        run.clock.advance(1_000)
        assertEquals(3, run.server.sent.size)
        run.clock.advance(1_999)
        assertEquals(3, run.server.sent.size)
        run.clock.advance(1)
        assertEquals(4, run.server.sent.size)
        assertEquals(setOf(fx.key(1).toString()), (1..3).map { run.server.key(it) }.toSet())
        assertEquals(1, run.presenter!!.presented.size)

        val down = Run(fx)
        down.server.answer(fx.exchange(0))
        repeat(4) { down.server.fail() }
        down.start()
        down.clock.advance(7_000)
        assertEquals(MeldReadyStatus.FAILED, down.result.status)
        assertEquals(MeldReadyError(MeldReadyErrorKind.TRANSPORT), down.result.error)
        assertEquals(1, (1..4).map { down.server.key(it) }.toSet().size)
    }

    @Test fun an_operation_in_flight_is_retried_with_the_same_key_after_a_second() {
        val fx = SessionFixture("banxa-fresh")
        val run = Run(fx)
        run.server.answer(fx.exchange(0))
        run.server.answer(425, mapOf("version" to 1, "code" to "OPERATION_IN_FLIGHT"))
        run.server.answer(fx.exchange(1))
        run.start()
        run.clock.advance(1_000)
        assertEquals(3, run.server.sent.size)
        assertEquals(run.server.key(1), run.server.key(2))
        assertEquals(1, run.presenter!!.presented.size)

        val stuck = Run(fx)
        stuck.server.answer(fx.exchange(0))
        repeat(6) { stuck.server.answer(425, mapOf("version" to 1, "code" to "OPERATION_IN_FLIGHT")) }
        stuck.start()
        stuck.clock.advance(5_000)
        assertEquals(MeldReadyError(MeldReadyErrorKind.HTTP, 425, "OPERATION_IN_FLIGHT"), stuck.result.error)
        assertEquals(7, stuck.server.sent.size)
    }

    @Test fun an_unknown_outcome_reads_first_and_a_step_that_writes_is_reported_not_sent_again() {
        val unknown = mapOf("version" to 1, "code" to "OUTCOME_UNKNOWN")
        @Suppress("UNCHECKED_CAST")
        fun fields(fx: SessionFixture, exchange: Int) = (fx.exchange(exchange)["request"] as Map<String, Any?>)["fields"] as Map<String, String>
        val handlers = { fx: SessionFixture, collected: Int ->
            MeldReadyHandlers(
                collect = { _, done -> done(fields(fx, collected)) },
                confirmAgreement = { _, done -> done(true) },
            )
        }
        val submit = SessionFixture("banxa-fresh")
        val agree = SessionFixture("banxa-uphold")
        val collect = SessionFixture("uphold-progressive-forms")
        val verify = SessionFixture("coinbase-verify-contact")
        for ((kind, case) in listOf(
            "SUBMIT" to Triple(submit, handlers(submit, 0), listOf(submit.exchange(0))),
            "AGREE" to Triple(agree, handlers(agree, 0), listOf(agree.exchange(0))),
            "COLLECT" to Triple(collect, handlers(collect, 1), listOf(collect.exchange(0))),
            "VERIFY_CONTACT send" to Triple(verify, handlers(verify, 3), listOf(verify.exchange(0))),
            "VERIFY_CONTACT confirm" to Triple(verify, handlers(verify, 3), listOf(verify.exchange(0), verify.exchange(1))),
        )) {
            val (fx, stepHandlers, before) = case
            val run = Run(fx, stepHandlers)
            before.forEach { run.server.answer(it) }
            run.server.answer(503, unknown)
            run.server.answer(fx.exchange(0))
            run.start()
            run.clock.advance(60_000)
            val runSteps = before.size
            assertEquals(kind, listOf("READ") + List(runSteps) { "RUN_STEP" } + "READ",
                run.server.sent.indices.map { run.server.body(it)["operation"] })
            assertEquals(kind, MeldReadyStatus.ACTION_REQUIRED, run.result.status)
            assertNull(kind, run.result.error)
            val step = (fx.response(0)["next"] as List<*>).first() as Map<*, *>
            assertEquals(kind, listOf(step["stepId"]), run.result.pending!!.map { it.stepId })
            assertEquals(kind, step["kind"], run.result.pending!!.single().kind)
            assertEquals(kind, 0, run.server.pending)
        }

        val gone = Run(submit)
        gone.server.answer(submit.exchange(0))
        gone.server.answer(503, unknown)
        gone.server.answer(SessionFixture("waiting-on-meld-kyc").exchange(0))
        gone.start()
        assertEquals("a step no longer offered has landed, so the run goes on", 3, gone.server.sent.size)
        assertTrue(gone.results.isEmpty())
        gone.loop.cancel()
    }

    @Test fun an_unknown_outcome_on_a_page_refresh_reads_first_and_refreshes_again_with_a_new_key() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val run = Run(fx)
        run.server.answer(fx.exchange(0))
        run.server.answer(503, mapOf("version" to 1, "code" to "OUTCOME_UNKNOWN"))
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.start()
        assertEquals(listOf("READ", "RUN_STEP", "READ", "RUN_STEP"), (0..3).map { run.server.body(it)["operation"] })
        assertEquals(run.server.body(1), run.server.body(3))
        assertNotEquals(run.server.key(1), run.server.key(3))
        assertEquals(listOf("https://onboarding.noah.test/hosted?session=fixture-2"), run.presenter!!.presented)
        run.loop.cancel()
    }

    @Test fun an_unknown_outcome_on_a_page_refresh_always_reads_first_and_is_resent_at_most_three_times() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val unknown = mapOf("version" to 1, "code" to "OUTCOME_UNKNOWN")
        val run = Run(fx)
        run.server.answer(fx.exchange(0))
        repeat(4) {
            run.server.answer(503, unknown)
            run.server.answer(fx.exchange(0))
        }
        run.start()
        assertEquals(listOf("READ") + List(4) { listOf("RUN_STEP", "READ") }.flatten(), (0..8).map { run.server.body(it)["operation"] })
        assertEquals(4, listOf(1, 3, 5, 7).map { run.server.key(it) }.toSet().size)
        assertEquals(MeldReadyStatus.FAILED, run.result.status)
        assertEquals(MeldReadyError(MeldReadyErrorKind.HTTP, 503, "OUTCOME_UNKNOWN"), run.result.error)

        val landed = Run(fx)
        landed.server.answer(fx.exchange(0))
        repeat(4) {
            landed.server.answer(503, unknown)
            landed.server.answer(if (it < 3) fx.exchange(0) else SessionFixture("waiting-on-meld-kyc").exchange(0))
        }
        landed.start()
        assertEquals(9, landed.server.sent.size)
        assertTrue("the fourth refresh had landed, so the run goes on", landed.results.isEmpty())
        landed.loop.cancel()
    }

    @Test fun an_unknown_outcome_whose_read_is_unauthorized_reads_again_on_the_renewed_session() {
        val unauthorized = mapOf("version" to 1, "code" to "AUTHORIZATION_REQUIRED")
        val unknown = mapOf("version" to 1, "code" to "OUTCOME_UNKNOWN")
        var renewals = 0
        fun renewing(fx: SessionFixture) = MeldReadyHandlers(renewSession = { done ->
            renewals += 1
            done(MeldRequirementsSession.fromMap(fx.mint + ("token" to "synthetic-renewed")))
        })

        val noah = SessionFixture("noah-hosted-onboarding")
        val refresh = Run(noah, renewing(noah))
        refresh.server.answer(noah.exchange(0))
        refresh.server.answer(503, unknown)
        refresh.server.answer(401, unauthorized)
        refresh.server.answer(noah.exchange(0))
        refresh.server.answer(noah.exchange(1))
        refresh.start()
        assertEquals(1, renewals)
        assertEquals(listOf("READ", "RUN_STEP", "READ", "READ", "RUN_STEP"), refresh.server.sent.indices.map { refresh.server.body(it)["operation"] })
        assertEquals("Bearer synthetic-renewed", refresh.server.sent[4].headers["Authorization"])
        assertNotEquals(refresh.server.key(1), refresh.server.key(4))
        assertEquals(listOf("https://onboarding.noah.test/hosted?session=fixture-2"), refresh.presenter!!.presented)
        assertTrue(refresh.results.isEmpty())
        refresh.loop.cancel()

        renewals = 0
        val banxa = SessionFixture("banxa-fresh")
        val submit = Run(banxa, renewing(banxa))
        submit.server.answer(banxa.exchange(0))
        submit.server.answer(503, unknown)
        submit.server.answer(401, unauthorized)
        submit.server.answer(banxa.exchange(0))
        submit.start()
        assertEquals(1, renewals)
        assertEquals(listOf("READ", "RUN_STEP", "READ", "READ"), submit.server.sent.indices.map { submit.server.body(it)["operation"] })
        assertEquals("Bearer synthetic-renewed", submit.server.sent[3].headers["Authorization"])
        assertEquals(MeldReadyStatus.ACTION_REQUIRED, submit.result.status)
        assertEquals(listOf(meldKyc), submit.result.pending!!.map { it.stepId })
        assertTrue(submit.presenter!!.presented.isEmpty())
    }

    @Test fun unauthorized_renews_the_session_once_and_otherwise_expires() {
        val fx = SessionFixture("unknown-step")
        val renewed = SessionFixture("uphold-fresh")
        val unauthorized = mapOf("version" to 1, "code" to "AUTHORIZATION_REQUIRED")
        var renewals = 0
        val run = Run(fx, MeldReadyHandlers(renewSession = { done ->
            renewals += 1
            done(MeldRequirementsSession.fromMap(renewed.mint + ("token" to "synthetic-renewed")))
        }))
        run.server.answer(401, unauthorized)
        run.server.answer(fx.exchange(0))
        run.start()
        assertEquals(1, renewals)
        assertEquals(renewed.endpoint, run.server.sent[1].url.toString())
        assertEquals("Bearer synthetic-renewed", run.server.sent[1].headers["Authorization"])
        assertEquals(MeldReadyStatus.UNDETERMINED, run.result.status)

        val again = Run(fx, MeldReadyHandlers(renewSession = { done -> renewals += 1; done(renewed.session) }))
        again.server.answer(401, unauthorized)
        again.server.answer(401, unauthorized)
        again.start()
        assertEquals(MeldReadyStatus.EXPIRED, again.result.status)
        assertEquals(2, renewals)

        for (handlers in listOf(MeldReadyHandlers(), MeldReadyHandlers(renewSession = { done -> done(null) }))) {
            val expired = Run(fx, handlers)
            expired.server.answer(401, null)
            expired.start()
            assertEquals(MeldReadyStatus.EXPIRED, expired.result.status)
            assertNull(expired.result.error)
        }
    }

    @Test fun unauthorized_on_a_step_renews_the_session_and_runs_the_step_again() {
        val fx = SessionFixture("banxa-fresh")
        val unauthorized = mapOf("version" to 1, "code" to "AUTHORIZATION_REQUIRED")
        var renewals = 0
        val run = Run(fx, MeldReadyHandlers(renewSession = { done ->
            renewals += 1
            done(MeldRequirementsSession.fromMap(fx.mint + ("token" to "synthetic-renewed")))
        }))
        run.keys += listOf(UUID.randomUUID(), fx.key(1))
        run.server.answer(fx.exchange(0))
        run.server.answer(401, unauthorized)
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.start()
        assertEquals(listOf("READ", "RUN_STEP", "READ", "RUN_STEP"), (0..3).map { run.server.body(it)["operation"] })
        assertEquals(1, renewals)
        assertEquals("Bearer synthetic-renewed", run.server.sent[3].headers["Authorization"])
        assertEquals(fx.exchange(1)["request"], run.server.body(3))
        assertNotEquals(run.server.key(1), run.server.key(3))
        assertEquals(listOf("https://in.sumsub.com/websdk/p/sbx_fixture_1"), run.presenter!!.presented)
        assertTrue(run.results.isEmpty())

        val uphold = SessionFixture("uphold-fresh")
        val asked = mutableListOf<MeldCollectRequest>()
        val form = Run(uphold, MeldReadyHandlers(
            collect = { request, done -> asked += request; done(mapOf("email" to "ada@example.com")) },
            renewSession = { done -> done(MeldRequirementsSession.fromMap(uphold.mint + ("token" to "synthetic-renewed"))) },
        ))
        form.server.answer(200, uphold.response(0).withNext(email))
        form.server.answer(401, unauthorized)
        form.server.answer(200, uphold.response(0).withNext(email))
        form.server.answer(uphold.exchange(3))
        form.server.answer(SessionFixture("unknown-step").exchange(0))
        form.start()
        assertEquals(2, asked.size)
        assertEquals(listOf("READ", "RUN_STEP", "READ", "RUN_STEP", "READ"), (0..4).map { form.server.body(it)["operation"] })
        assertEquals(uphold.exchange(3)["request"], form.server.body(3))
        assertEquals("Bearer synthetic-renewed", form.server.sent[3].headers["Authorization"])
        assertEquals(MeldReadyStatus.UNDETERMINED, form.result.status)
    }

    @Test fun renew_session_is_asked_at_most_three_times_per_run() {
        val fx = SessionFixture("waiting-on-meld-kyc")
        val unauthorized = mapOf("version" to 1, "code" to "AUTHORIZATION_REQUIRED")
        var renewals = 0
        val run = Run(fx, MeldReadyHandlers(renewSession = { done -> renewals += 1; done(fx.session) }))
        repeat(3) {
            run.server.answer(401, unauthorized)
            run.server.answer(fx.exchange(0))
        }
        run.server.answer(401, unauthorized)
        run.start()
        run.clock.advance(60_000)
        assertEquals(3, renewals)
        assertEquals(7, run.server.sent.size)
        assertEquals(MeldReadyStatus.EXPIRED, run.result.status)
        assertNull(run.result.error)
    }

    @Test fun other_errors_fail_with_their_status_and_code_and_a_bad_answer_is_a_protocol_failure() {
        val fx = SessionFixture("banxa-fresh")
        val cases = listOf(
            listOf(502 to mapOf("version" to 1, "code" to "PROVIDER_UNAVAILABLE")) to MeldReadyError(MeldReadyErrorKind.HTTP, 502, "PROVIDER_UNAVAILABLE"),
            listOf(500 to "<html>") to MeldReadyError(MeldReadyErrorKind.HTTP, 500),
            listOf(404 to mapOf("version" to 1, "code" to "STATE_NOT_FOUND")) to MeldReadyError(MeldReadyErrorKind.HTTP, 404, "STATE_NOT_FOUND"),
            listOf(409 to mapOf("version" to 1, "code" to "CONCURRENT_STATE_CHANGE")) to
                MeldReadyError(MeldReadyErrorKind.HTTP, 409, "CONCURRENT_STATE_CHANGE"),
            listOf(200 to """{"version":2}""") to MeldReadyError(MeldReadyErrorKind.PROTOCOL),
            listOf(200 to "not json") to MeldReadyError(MeldReadyErrorKind.PROTOCOL),
            listOf(200 to (fx.response(0) - "next")) to MeldReadyError(MeldReadyErrorKind.PROTOCOL),
            listOf(200 to (fx.response(0) + ("next" to listOf(mapOf("stepId" to meldKyc))))) to MeldReadyError(MeldReadyErrorKind.PROTOCOL),
            listOf(200 to fx.response(0), 200 to mapOf("version" to 1, "stepId" to "other", "outcome" to "DONE")) to
                MeldReadyError(MeldReadyErrorKind.PROTOCOL),
            listOf(200 to fx.response(0), 200 to (fx.response(1) + ("url" to "http://in.sumsub.com/websdk/p/x"))) to
                MeldReadyError(MeldReadyErrorKind.PROTOCOL),
            listOf(200 to fx.response(0), 400 to mapOf("version" to 1, "code" to "INVALID_REQUEST")) to
                MeldReadyError(MeldReadyErrorKind.HTTP, 400, "INVALID_REQUEST"),
        )
        for ((answers, error) in cases) {
            val run = Run(fx)
            for ((status, body) in answers) run.server.answer(status, body)
            run.start()
            assertEquals("$answers", MeldReadyStatus.FAILED, run.result.status)
            assertEquals("$answers", error, run.result.error)
            assertTrue(run.presenter!!.presented.isEmpty())
        }
    }

    @Test fun a_hosted_step_needs_a_presenter_and_a_page_that_will_not_open_counts_as_closed() {
        val fx = SessionFixture("noah-hosted-onboarding")
        for (presenter in listOf(null, FakePresenter(available = false))) {
            val run = Run(fx, presenter = presenter)
            run.server.answer(fx.exchange(0))
            run.start()
            assertEquals(MeldReadyStatus.ACTION_REQUIRED, run.result.status)
            assertEquals(listOf(noahStep), run.result.pending!!.map { it.stepId })
            assertEquals(1, run.server.sent.size)
        }

        val refused = Run(fx, presenter = FakePresenter(opens = false))
        refused.server.answer(fx.exchange(0))
        refused.server.answer(fx.exchange(1))
        refused.server.answer(fx.exchange(2))
        refused.server.answer(fx.exchange(3))
        refused.start()
        assertEquals(listOf("READ", "RUN_STEP", "RUN_STEP", "READ"), (0..3).map { refused.server.body(it)["operation"] })
    }

    @Test fun present_url_replaces_the_browser_and_its_callback_means_closed() {
        val fx = SessionFixture("noah-hosted-onboarding")
        val requests = mutableListOf<MeldPresentUrlRequest>()
        var closed: (() -> Unit)? = null
        val run = Run(fx, MeldReadyHandlers(presentUrl = { request, done -> requests += request; closed = done }), presenter = null)
        run.server.answer(fx.exchange(0))
        run.server.answer(fx.exchange(1))
        run.server.answer(fx.exchange(2))
        run.server.answer(fx.exchange(3))
        run.start()
        assertEquals(listOf(MeldPresentUrlRequest(noahStep, "https://onboarding.noah.test/hosted?session=fixture-2", "2026-11-04T16:00:05Z")),
            requests)
        assertEquals("[MeldPresentUrlRequest REDACTED]", requests.single().toString())
        closed!!()
        closed!!()
        run.clock.advance()
        assertEquals(4, run.server.sent.size)
        assertEquals(mapOf("version" to 1, "operation" to "RUN_STEP", "stepId" to noahStep, "refresh" to true), run.server.body(2))
    }

    @Test fun error_codes_are_kept_only_as_a_non_empty_list_of_strings() {
        val read = SessionFixture("coinme-share-refused").response(0)

        @Suppress("UNCHECKED_CAST")
        fun share(codes: Any?): MeldReadyOpenStep {
            val provider = (read["providers"] as List<Map<String, Any?>>).single()
            val requirements = (provider["requirements"] as List<Map<String, Any?>>).toMutableList()
            requirements[3] = requirements[3] + ("errorCodes" to codes)
            val changed = read + ("providers" to listOf(provider + ("requirements" to requirements)))
            return checkNotNull(RequirementsSnapshot.parse(changed)).results().single().open.first()
        }
        assertEquals(listOf("PHONE_VERIFICATION_REQUIRED", "NEWER_CODE"), share(listOf("PHONE_VERIFICATION_REQUIRED", "NEWER_CODE")).errorCodes)
        for (codes in listOf(null, emptyList<String>(), "PHONE_VERIFICATION_REQUIRED", listOf("PHONE_VERIFICATION_REQUIRED", 7),
            mapOf("code" to "X"))) {
            val refused = share(codes)
            assertNull("$codes", refused.errorCodes)
            assertEquals("Coinme needs the customer's phone verified first", refused.reason)
            assertEquals("BLOCKED", refused.status)
        }
    }

    @Test fun continuation_lists_the_order_steps_left_for_mount() {
        val fx = SessionFixture("unknown-step")
        val stripe = mapOf("type" to "PROVIDER_PROCESS", "code" to "STRIPE_IDENTITY", "status" to "REQUIRED", "timing" to "ORDER_CONTINUATION",
            "action" to mapOf("type" to "SDK_STEP", "nextStep" to "STRIPE_IDENTITY_VERIFICATION"))
        val done = stripe + ("code" to "DONE_ALREADY") + ("status" to "SATISFIED")
        val read = fx.response(0) + ("status" to "READY") + ("providers" to listOf(
            provider("STRIPE", "READY", true, listOf(stripe, done))))
        val run = Run(fx)
        run.server.answer(200, read)
        run.start()
        assertEquals(listOf(MeldReadyContinuation("STRIPE", "STRIPE_IDENTITY", "STRIPE_IDENTITY_VERIFICATION")), run.result.continuation)
        assertEquals(emptyList<MeldReadyOpenStep>(), run.result.providers.single().open)
    }

    @Test fun a_read_may_answer_up_to_256_kib() {
        val fx = SessionFixture("unknown-step")
        val envelope = jsonOf(fx.response(0) + ("pad" to "")).toString().length
        val fits = Run(fx)
        fits.server.answer(200, fx.response(0) + ("pad" to "x".repeat(262_144 - envelope)))
        fits.start()
        assertEquals(MeldReadyStatus.UNDETERMINED, fits.result.status)

        val over = Run(fx)
        over.server.answer(200, fx.response(0) + ("pad" to "x".repeat(262_145 - envelope)))
        over.start()
        assertEquals(MeldReadyError(MeldReadyErrorKind.PROTOCOL), over.result.error)
    }

    @Test fun a_session_minted_in_another_environment_is_refused_with_401_and_expires_after_one_renewal() {
        val fx = SessionFixture("banxa-fresh")
        val unauthorized = mapOf("version" to 1, "code" to "AUTHORIZATION_REQUIRED")
        var renewals = 0
        val run = Run(fx, MeldReadyHandlers(renewSession = { done -> renewals += 1; done(fx.session) }), environment = MeldEnvironment.PRODUCTION)
        run.server.answer(401, unauthorized)
        run.server.answer(401, unauthorized)
        run.start()
        assertEquals(fx.endpoint.replace("https://api-sb.meld.io", "https://api.meld.io"), run.server.sent[0].url.toString())
        assertEquals(1, renewals)
        assertEquals(2, run.server.sent.size)
        assertEquals(MeldReadyStatus.EXPIRED, run.result.status)
        assertNull(run.result.error)
    }

    @Test fun an_endpoint_off_the_configured_api_host_fails_before_any_request() {
        val fx = SessionFixture("banxa-fresh")
        @Suppress("UNCHECKED_CAST")
        val sandbox = fx.mint + ("requirementsActions" to (fx.mint["requirementsActions"] as Map<String, Any?>) + ("endpoint" to fx.endpoint))
        val run = Run(fx, environment = MeldEnvironment.PRODUCTION)
        run.start(MeldRequirementsSession.fromMap(sandbox))
        assertEquals(MeldReadyError(MeldReadyErrorKind.PROTOCOL), run.result.error)
        assertTrue(run.server.sent.isEmpty())
    }

    @Test fun pending_steps_never_print_their_links() {
        val step = MeldReadyStep("NOAH:EXTERNAL_KYC:X", "OPEN_URL", "EXTERNAL_KYC", "X", listOf("NOAH"), url = "https://synthetic.test/secret")
        assertEquals("[MeldReadyStep REDACTED]", step.toString())
        assertFalse(MeldReadyResult(MeldReadyStatus.ACTION_REQUIRED, emptyList(), listOf(step)).toString().contains("secret"))
        assertEquals("MeldReadyProgress(state=READING)", MeldReadyProgress(MeldReadyState.READING, mapOf("url" to "secret")).toString())
    }

    @Test fun callbacks_run_on_main_once_and_cancel_drops_the_request_in_flight() {
        val fx = SessionFixture("uphold-fresh")
        val main = QueuedMain()
        val testThread = Thread.currentThread()
        val server = ScriptedServer()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = mutableListOf<Thread>()
        val results = mutableListOf<MeldReadyResult>()
        val executor = ActionQueue.newActionsExecutor()
        val loop = RequirementsLoop(MeldEnvironment.SANDBOX, MeldReadyHandlers(
            onProgress = { threads += Thread.currentThread() },
            confirmAgreement = { _, done ->
                threads += Thread.currentThread()
                Thread { done(true) }.apply { start(); join() }
            },
        ), MeldReadyOptions(), FakePresenter(), { threads += Thread.currentThread(); results += it }, main) { SessionTransport(it, ActionQueue(executor, main), server.connector) }
        server.answer(200, fx.response(0).withNext(terms))
        server.answer(200, mapOf("version" to 1, "stepId" to terms, "outcome" to "DONE"), onRead = {
            reading.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        loop.start(fx.session)
        assertTrue(main.awaitPosted())
        main.drain()
        assertTrue(main.awaitPosted())
        main.drain()
        assertTrue(reading.await(5, TimeUnit.SECONDS))

        loop.cancel()
        assertEquals(MeldReadyStatus.CANCELLED, results.single().status)
        assertTrue(server.sent[1].disconnected >= 1)
        release.countDown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(0, main.drain())
        assertEquals(1, results.size)
        assertTrue(threads.size >= 4)
        assertTrue(threads.all { it === testThread })
        assertEquals(2, server.sent.size)
    }

    @Test fun nothing_is_asked_shown_or_sent_after_on_progress_cancelled_the_run() {
        val banxa = SessionFixture("banxa-fresh")
        val uphold = SessionFixture("uphold-fresh")
        val noah = SessionFixture("noah-hosted-onboarding")
        val waiting = SessionFixture("waiting-on-meld-kyc")
        val read = 200 to banxa.response(0)
        val cases = listOf(
            cancelledOn(MeldReadyState.READING, banxa, listOf(read)) to 0,
            cancelledOn(MeldReadyState.RUNNING, banxa, listOf(read)) to 1,
            cancelledOn(MeldReadyState.AWAITING_INPUT, uphold, listOf(200 to uphold.response(0).withNext(email))) to 1,
            cancelledOn(MeldReadyState.AWAITING_INPUT, uphold, listOf(200 to uphold.response(0).withNext(terms))) to 1,
            cancelledOn(MeldReadyState.PRESENTING, banxa, listOf(read, 200 to banxa.response(1))) to 2,
            cancelledOn(MeldReadyState.PRESENTING, noah, listOf(200 to noah.response(0)), presentUrl = true,
                wallStart = "2026-11-04T14:59:40Z") to 1,
            cancelledOn(MeldReadyState.WAITING, waiting, List(2) { 200 to waiting.response(0) }) to 1,
        )
        for ((cancelled, sent) in cases) {
            val (state, run, called) = cancelled
            assertEquals("$state", MeldReadyStatus.CANCELLED, run.result.status)
            assertEquals("$state", emptyList<String>(), called)
            assertEquals("$state", sent, run.server.sent.size)
            assertEquals("$state", emptyList<String>(), run.presenter!!.presented)
            assertFalse("$state", run.presenter.showing)
        }
    }

    /** A run whose onProgress cancels it on [state]. The list names every handler called after that. */
    private fun cancelledOn(
        state: MeldReadyState,
        fixture: SessionFixture,
        answers: List<Pair<Int, Any?>>,
        presentUrl: Boolean = false,
        wallStart: String = fixture.exchange(0)["at"] as String,
    ): Triple<MeldReadyState, Run, List<String>> {
        val called = mutableListOf<String>()
        lateinit var run: Run
        run = Run(fixture, MeldReadyHandlers(
            onProgress = { if (it.state == state) run.loop.cancel() },
            collect = { _, _ -> called += "collect" },
            confirmAgreement = { _, _ -> called += "confirmAgreement" },
            presentUrl = if (presentUrl) { _, _ -> called += "presentUrl" } else null,
        ), wallStart = wallStart)
        for ((status, body) in answers) run.server.answer(status, body)
        run.start()
        run.clock.advance(60_000)
        return Triple(state, run, called)
    }

    @Test fun a_handle_kept_after_the_result_holds_neither_the_activity_nor_the_handlers() {
        val (handle, activity) = destroyedWhileAPageIsOpen()
        assertTrue("the destroyed activity can be collected while the handle is kept", collected(activity))
        handle.refresh()
        handle.cancel()
    }

    /** As Meld.ensureReady wires it: the activity's lifecycle, a presenter on it, and handlers that capture it. */
    private fun destroyedWhileAPageIsOpen(): Pair<MeldReadyHandle, WeakReference<Any>> {
        val fx = SessionFixture("noah-hosted-onboarding")
        val activity = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        val launcher = object : BrowserLauncher {
            override fun canOpen() = activity.lifecycle.currentState != Lifecycle.State.DESTROYED

            override fun open(url: String) = canOpen()
        }
        val clock = VirtualClock()
        val server = ScriptedServer()
        val results = mutableListOf<MeldReadyResult>()
        val wall = checkNotNull(parseRfc3339("2026-11-04T14:59:40Z"))
        val loop = RequirementsLoop(MeldEnvironment.SANDBOX, MeldReadyHandlers(
            onProgress = { activity.hashCode() },
            collect = { _, _ -> activity.hashCode() },
            renewSession = { activity.hashCode() },
        ), MeldReadyOptions(), BrowserPresenter(activity.lifecycle, launcher), { results += it; activity.hashCode() }, clock,
            { wall + clock.time }) { SessionTransport(it, ActionQueue(DirectExecutor(), clock), server.connector) }
        server.answer(fx.exchange(0))
        loop.bind(activity.lifecycle)
        loop.start(fx.session)
        clock.advance()
        assertEquals("the run and the open page observe the activity", 2, activity.registry.observerCount)
        activity.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertEquals(MeldReadyStatus.CANCELLED, results.single().status)
        return loop to WeakReference(activity)
    }

    private fun collected(reference: WeakReference<*>): Boolean {
        repeat(50) {
            if (reference.get() == null) return true
            System.gc()
            Thread.sleep(10)
        }
        return reference.get() == null
    }

    @Test fun destroying_the_activity_cancels_the_run() {
        val fx = SessionFixture("waiting-on-meld-kyc")
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val run = Run(fx)
        run.server.answer(fx.exchange(0))
        run.loop.bind(owner.lifecycle)
        run.start()
        assertTrue(run.results.isEmpty())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertEquals(MeldReadyStatus.CANCELLED, run.result.status)
        run.clock.advance(60_000)
        assertEquals(1, run.server.sent.size)

        val late = Run(fx)
        late.loop.bind(owner.lifecycle)
        late.start()
        assertEquals(MeldReadyStatus.CANCELLED, late.result.status)
        assertTrue(late.server.sent.isEmpty())
    }

    private fun provider(serviceProvider: String, status: String, eligible: Boolean?, requirements: List<Map<String, Any?>> = emptyList()) =
        mapOf("serviceProvider" to serviceProvider, "status" to status, "eligible" to eligible, "requirements" to requirements)
}
