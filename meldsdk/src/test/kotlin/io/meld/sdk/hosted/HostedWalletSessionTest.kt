package io.meld.sdk.hosted

import android.view.ViewGroup
import io.meld.sdk.MeldError
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldWidgetHandle
import io.meld.sdk.TerminalGate
import io.meld.sdk.gated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * The session that runs a provider-hosted wallet page offscreen, driven by a synthetic page on a
 * virtual clock. No page is loaded and no sheet is shown.
 */
class HostedWalletSessionTest {

    private class VirtualClock : Scheduler {
        private class Task(val at: Long, val order: Int, val work: () -> Unit)

        private val tasks = mutableListOf<Task>()
        private val owner = Thread.currentThread()
        private var scheduled = 0
        var time = 0L
            private set

        override val isCurrentThread: Boolean get() = Thread.currentThread() === owner

        override fun now(): Long = time

        override fun schedule(delayMs: Long, work: () -> Unit): Cancellable {
            val task = Task(time + delayMs, scheduled++, work)
            tasks += task
            return Cancellable { tasks -= task }
        }

        fun advance(ms: Long = 0) {
            val end = time + ms
            while (true) {
                val next = tasks.filter { it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.order })) ?: break
                tasks -= next
                time = next.at
                next.work()
            }
            time = end
        }
    }

    private class FakeForeground : HostForeground {
        override var resumed = true
        private val listeners = mutableListOf<Pair<() -> Unit, () -> Unit>>()

        override fun observe(onPaused: () -> Unit, onResumed: () -> Unit): Cancellable {
            val listener = onPaused to onResumed
            listeners += listener
            return Cancellable { listeners -= listener }
        }

        fun pause() {
            resumed = false
            listeners.toList().forEach { it.first() }
        }

        fun resume() {
            resumed = true
            listeners.toList().forEach { it.second() }
        }
    }

    private class FakeSurface(override val foreground: FakeForeground) : HostedSurface {
        override val container: ViewGroup = object : ViewGroup(null) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        }
        override var onHostGone: (() -> Unit)? = null
        var removals = 0

        override fun remove() {
            removals += 1
            onHostGone = null
        }

        fun hostLeaves() = onHostGone?.invoke()
    }

    private class FakePage(private val clock: VirtualClock, private val log: MutableList<String>) : HostedPage {
        val answers = ArrayDeque<String?>()
        val unanswered = mutableListOf<(String?) -> Unit>()
        var presses = 0
        var unmounts = 0
        var mountedInto: ViewGroup? = null

        override fun mount(host: ViewGroup) {
            mountedInto = host
        }

        override fun unmount() {
            unmounts += 1
            log += "unmount"
        }

        override fun evaluateJavascript(script: String, callback: (String?) -> Unit) {
            assertEquals(SCRIPT, script)
            presses += 1
            val answer = answers.removeFirstOrNull() ?: "missing"
            if (answer == HANG) unanswered += callback else clock.schedule(0) { callback(answer) }
        }
    }

    private class Harness(timing: HostedWalletSession.Timing = FAST) {
        val clock = VirtualClock()
        val log = mutableListOf<String>()
        val errors = mutableListOf<MeldError>()
        val gate = TerminalGate()
        val handlers = MeldEventHandlers(
            onReady = { log += "ready" },
            onPaymentSubmitted = { log += "submitted" },
            onStatusChange = { log += "status:${it.status.raw}" },
            onCancel = { log += "cancel" },
            onError = {
                errors += it
                log += "error:${it.code}"
            },
        ).gated(gate)
        val foreground = FakeForeground()
        val surface = FakeSurface(foreground)
        val page = FakePage(clock, log)
        val surfaces = DetachedSurfaces(clock)
        val session = HostedWalletSession("o1", handlers, PROFILE, SCRIPT, timing, clock, surfaces)

        init {
            session.start(page, surface)
        }

        fun send(eventName: String, data: String = "{}") = session.receive(message(eventName, data))

        fun presented(): Harness {
            page.answers += "clicked"
            send("onramp_api.load_success")
            clock.advance()
            assertEquals(listOf("ready"), log)
            return this
        }
    }

    // MARK: - Mount

    @Test
    fun `the page is mounted into the surface and nothing is reported until it is pressed`() {
        val h = Harness()

        assertSame(h.surface.container, h.page.mountedInto)
        assertTrue(h.log.isEmpty())
        assertEquals(0, h.page.presses)
    }

    // MARK: - Pressing

    @Test
    fun `ready presses the button once and reports ready on the press, not on the page load`() {
        val h = Harness()
        h.page.answers += "clicked"

        h.send("onramp_api.load_success")
        assertTrue("the press answers asynchronously", h.log.isEmpty())
        h.clock.advance()

        assertEquals(1, h.page.presses)
        assertEquals(listOf("ready"), h.log)
    }

    @Test
    fun `a missing button is asked for again until it appears, pressing nothing in between`() {
        val h = Harness()
        h.page.answers += listOf("missing", null, "clicked")

        h.send("onramp_api.load_success")
        h.clock.advance(FAST.pressIntervalMs * 3)

        assertEquals(3, h.page.presses)
        assertEquals(listOf("ready"), h.log)
    }

    @Test
    fun `a button that never appears fails the presentation within the press budget`() {
        val h = Harness()

        h.send("onramp_api.load_success")
        h.clock.advance(FAST.pressIntervalMs * FAST.pressAttempts)

        assertEquals(FAST.pressAttempts, h.page.presses)
        assertEquals(listOf("error:PRESENTATION_FAILED", "unmount"), h.log)
        assertEquals("pay_button_not_found", h.errors.single().detail)
        assertFalse(h.errors.single().attemptMayExist)
        assertEquals(1, h.surface.removals)
    }

    @Test
    fun `a pressed page is never pressed again, whatever it sends`() {
        val h = Harness().presented()

        h.send("onramp_api.load_success")
        h.send("onramp_api.load_success")
        h.clock.advance(FAST.pressIntervalMs * 5)

        assertEquals(1, h.page.presses)
        assertEquals(listOf("ready"), h.log)
    }

    @Test
    fun `a ready while the press is in flight does not press twice`() {
        val h = Harness()
        h.page.answers += "clicked"

        h.send("onramp_api.load_success")
        h.send("onramp_api.load_success")
        h.clock.advance()

        assertEquals(1, h.page.presses)
        assertEquals(listOf("ready"), h.log)
    }

    // MARK: - Terminal callbacks

    @Test
    fun `cancel gives exactly one onCancel then teardown`() {
        val h = Harness().presented()

        h.send("onramp_api.cancel")
        h.send("onramp_api.cancel")

        assertEquals(listOf("ready", "cancel", "unmount"), h.log)
    }

    @Test
    fun `a wallet the device cannot pay with is unavailable, with no attempt`() {
        val h = Harness().presented()

        h.send("onramp_api.load_error", """{"errorCode":"ERROR_CODE_GUEST_GOOGLE_PAY_NOT_SUPPORTED"}""")

        assertEquals(listOf("ready", "error:PAYMENT_METHOD_UNAVAILABLE", "unmount"), h.log)
        assertEquals("onramp_api.load_error:ERROR_CODE_GUEST_GOOGLE_PAY_NOT_SUPPORTED", h.errors.single().detail)
        assertFalse(h.errors.single().attemptMayExist)
    }

    @Test
    fun `a load failure before the press is a presentation failure`() {
        val h = Harness()

        h.session.pageFailed(MeldError("o1", "PROVIDER_LOAD_FAILED", "offline", "errorCode #-2", recoverable = true))

        assertEquals(listOf("error:PRESENTATION_FAILED", "unmount"), h.log)
        assertEquals("PROVIDER_LOAD_FAILED:errorCode #-2", h.errors.single().detail)
        assertEquals("offline", h.errors.single().message)
        assertFalse(h.errors.single().recoverable)
    }

    @Test
    fun `a load failure after the press is an unknown outcome`() {
        val h = Harness().presented()

        h.session.pageFailed(MeldError("o1", "PROVIDER_LOAD_FAILED", "offline", "errorCode #-2", recoverable = true))

        assertEquals(listOf("ready", "error:PAYMENT_OUTCOME_UNKNOWN", "unmount"), h.log)
        assertTrue(h.errors.single().attemptMayExist)
    }

    @Test
    fun `submission is pending then submitted, later polling is dropped, and polling success settles`() {
        val h = Harness().presented()

        h.send("onramp_api.commit_success")
        h.send("onramp_api.polling_start")
        assertEquals(listOf("ready", "status:pending", "submitted"), h.log)
        assertEquals("the page keeps polling after the terminal callback", 0, h.page.unmounts)

        h.send("onramp_api.polling_success")
        assertEquals(listOf("ready", "status:pending", "submitted", "unmount"), h.log)
    }

    @Test
    fun `polling success without a commit is the submission`() {
        val h = Harness().presented()

        h.send("onramp_api.polling_success")

        assertEquals(listOf("ready", "status:completed", "submitted", "unmount"), h.log)
    }

    // MARK: - Renderer gone

    @Test
    fun `the renderer going before the press is a presentation failure`() {
        val h = Harness()

        h.session.pageTerminated()
        h.session.pageTerminated()

        assertEquals(listOf("error:PRESENTATION_FAILED", "unmount"), h.log)
        assertEquals("render_process_gone", h.errors.single().detail)
    }

    @Test
    fun `the renderer going while the press is in flight is an unknown outcome`() {
        val h = Harness()
        h.page.answers += "clicked"
        h.send("onramp_api.load_success")

        h.session.pageTerminated()
        h.clock.advance()

        assertEquals(listOf("error:PAYMENT_OUTCOME_UNKNOWN", "unmount"), h.log)
        assertEquals("render_process_gone", h.errors.single().detail)
        assertTrue(h.errors.single().attemptMayExist)
    }

    @Test
    fun `a load failure while the press is in flight is an unknown outcome`() {
        val h = Harness()
        h.page.answers += "clicked"
        h.send("onramp_api.load_success")

        h.session.pageFailed(MeldError("o1", "PROVIDER_LOAD_FAILED", "offline", "errorCode #-2", recoverable = true))
        h.clock.advance()

        assertEquals(listOf("error:PAYMENT_OUTCOME_UNKNOWN", "unmount"), h.log)
        assertTrue(h.errors.single().attemptMayExist)
    }

    @Test
    fun `the host leaving while the press is in flight is an unknown outcome`() {
        val h = Harness()
        h.page.answers += "clicked"
        h.send("onramp_api.load_success")

        h.surface.hostLeaves()
        h.clock.advance()

        assertEquals(listOf("error:PAYMENT_OUTCOME_UNKNOWN", "unmount"), h.log)
        assertEquals("host_detached", h.errors.single().detail)
        assertTrue(h.errors.single().attemptMayExist)
    }

    @Test
    fun `the host leaving between presses is a presentation failure`() {
        val h = Harness()
        h.page.answers += "missing"
        h.send("onramp_api.load_success")
        h.clock.advance()

        h.surface.hostLeaves()

        assertEquals(listOf("error:PRESENTATION_FAILED", "unmount"), h.log)
        assertFalse(h.errors.single().attemptMayExist)
    }

    @Test
    fun `a press the page never answers ends at the press timeout with an unknown outcome`() {
        val h = Harness()
        h.page.answers += HANG
        h.send("onramp_api.load_success")

        h.clock.advance(FAST.pressTimeoutMs)

        assertEquals(listOf("error:PAYMENT_OUTCOME_UNKNOWN", "unmount"), h.log)
        assertEquals("press_timeout", h.errors.single().detail)
        assertTrue(h.errors.single().attemptMayExist)
        assertEquals(1, h.surface.removals)

        h.page.unanswered.single().invoke("clicked")
        h.clock.advance(FAST.presentationCeilingMs)
        assertEquals("a late answer changes nothing", listOf("error:PAYMENT_OUTCOME_UNKNOWN", "unmount"), h.log)
    }

    @Test
    fun `the press timeout stops once the button is pressed`() {
        val h = Harness().presented()

        h.clock.advance(FAST.pressTimeoutMs)

        assertEquals(listOf("ready"), h.log)
    }

    @Test
    fun `the renderer going after the press reports an unknown outcome through the backstop`() {
        val h = Harness().presented()

        h.session.pageTerminated()
        h.session.pageTerminated()

        assertEquals(listOf("ready", "unmount", "error:PAYMENT_OUTCOME_UNKNOWN"), h.log)
        assertEquals("session_ended", h.errors.single().detail)
    }

    @Test
    fun `the renderer going after submission only tears down`() {
        val h = Harness().presented()
        h.send("onramp_api.commit_success")

        h.session.pageTerminated()

        assertEquals(listOf("ready", "status:pending", "submitted", "unmount"), h.log)
    }

    // MARK: - Timers

    @Test
    fun `no ready in time is a presentation failure`() {
        val h = Harness()

        h.clock.advance(FAST.loadTimeoutMs)

        assertEquals(listOf("error:PRESENTATION_FAILED", "unmount"), h.log)
        assertEquals("load_timeout", h.errors.single().detail)
    }

    @Test
    fun `ready stops the load timeout`() {
        val h = Harness().presented()
        h.foreground.pause()

        h.clock.advance(FAST.loadTimeoutMs * 2)

        assertEquals(listOf("ready"), h.log)
    }

    @Test
    fun `a sheet that never appears fails at the presentation deadline`() {
        val h = Harness().presented()

        h.clock.advance(FAST.presentationDeadlineMs)

        assertEquals(listOf("ready", "error:PRESENTATION_FAILED", "unmount"), h.log)
        assertEquals("presentation_deadline", h.errors.single().detail)
    }

    @Test
    fun `any page event disarms the presentation deadline`() {
        val h = Harness().presented()

        h.send("onramp_api.something_new")
        h.clock.advance(FAST.presentationDeadlineMs + 1)

        assertEquals(listOf("ready"), h.log)
    }

    @Test
    fun `the host pausing for the sheet disarms the presentation deadline`() {
        val h = Harness().presented()

        h.foreground.pause()
        h.clock.advance(FAST.presentationCeilingMs * 2)

        assertEquals(listOf("ready"), h.log)
    }

    @Test
    fun `a sheet up before the press answers starts neither timer until the host resumes`() {
        val h = Harness()
        h.page.answers += "clicked"
        h.foreground.pause()

        h.send("onramp_api.load_success")
        h.clock.advance(FAST.presentationCeilingMs * 2)
        assertEquals(listOf("ready"), h.log)

        h.foreground.resume()
        h.clock.advance(FAST.presentationCeilingMs)

        assertEquals(listOf("ready", "error:PAYMENT_OUTCOME_UNKNOWN", "unmount"), h.log)
        assertEquals("presentation_ceiling", h.errors.single().detail)
    }

    @Test
    fun `the ceiling counts only time the host is in front`() {
        val h = Harness().presented()
        h.send("onramp_api.something_new")

        h.clock.advance(FAST.presentationCeilingMs - 100)
        h.foreground.pause()
        h.clock.advance(FAST.presentationCeilingMs * 3)
        assertEquals("time with the sheet up does not count", listOf("ready"), h.log)

        h.foreground.resume()
        h.clock.advance(99)
        assertEquals(listOf("ready"), h.log)
        h.clock.advance(1)

        assertEquals(listOf("ready", "error:PAYMENT_OUTCOME_UNKNOWN", "unmount"), h.log)
    }

    @Test
    fun `submission stops the deadline and the ceiling`() {
        val h = Harness().presented()

        h.send("onramp_api.commit_success")
        h.clock.advance(FAST.presentationCeilingMs * 2)

        assertEquals(listOf("ready", "status:pending", "submitted"), h.log)
    }

    // MARK: - Keep-alive after submission and host departure

    @Test
    fun `a released submitted page is kept until polling settles`() {
        for (outcome in listOf("onramp_api.polling_success", "onramp_api.polling_error", "onramp_api.cancel")) {
            val h = Harness().presented()
            val handle = MeldWidgetHandle("native-wallet", h.session, h.gate)
            h.send("onramp_api.commit_success")

            handle.unmount()
            handle.unmount()
            assertEquals(outcome, 1, h.surfaces.count)
            assertEquals(outcome, 0, h.page.unmounts)

            h.send(outcome, """{"errorCode":"ERROR_CODE_X"}""")

            assertEquals(outcome, 0, h.surfaces.count)
            assertEquals(outcome, listOf("ready", "status:pending", "submitted", "unmount"), h.log)
        }
    }

    @Test
    fun `a released submitted page that never settles is torn down after the grace period`() {
        val h = Harness().presented()
        h.send("onramp_api.commit_success")

        h.session.unmount()
        h.clock.advance(FAST.detachedGraceMs - 1)
        assertEquals(1, h.surfaces.count)
        h.clock.advance(1)

        assertEquals(0, h.surfaces.count)
        assertEquals(1, h.page.unmounts)
        assertEquals(listOf("ready", "status:pending", "submitted", "unmount"), h.log)
    }

    @Test
    fun `a page released before submission is torn down at once with no callback`() {
        val h = Harness().presented()
        val handle = MeldWidgetHandle("native-wallet", h.session, h.gate)

        handle.unmount()
        h.session.pageTerminated()

        assertEquals(0, h.surfaces.count)
        assertEquals(listOf("ready", "unmount"), h.log)
        assertEquals(1, h.surface.removals)
    }

    @Test
    fun `an unmount from another thread tears down on the session's thread`() {
        val h = Harness().presented()
        val handle = MeldWidgetHandle("native-wallet", h.session, h.gate)

        thread { handle.unmount() }.join()
        assertEquals(0, h.page.unmounts)
        h.clock.advance()

        assertEquals(1, h.page.unmounts)
        assertEquals(1, h.surface.removals)
        assertEquals(listOf("ready", "unmount"), h.log)
    }

    @Test
    fun `a submitted page released from another thread is kept on the session's thread`() {
        val h = Harness().presented()
        h.send("onramp_api.commit_success")

        thread { h.session.unmount() }.join()
        assertEquals(0, h.surfaces.count)
        h.clock.advance()
        assertEquals(1, h.surfaces.count)
        h.send("onramp_api.polling_success")

        assertEquals(0, h.surfaces.count)
        assertEquals(listOf("ready", "status:pending", "submitted", "unmount"), h.log)
    }

    @Test
    fun `the host leaving before the press is a presentation failure with no attempt`() {
        val h = Harness()

        h.surface.hostLeaves()
        h.send("onramp_api.commit_success")

        assertEquals(listOf("error:PRESENTATION_FAILED", "unmount"), h.log)
        assertEquals("host_detached", h.errors.single().detail)
        assertFalse(h.errors.single().attemptMayExist)
        assertNull(h.surface.onHostGone)
    }

    @Test
    fun `the host leaving after the integrator unmounted reports nothing`() {
        val h = Harness().presented()
        MeldWidgetHandle("native-wallet", h.session, h.gate).unmount()

        h.surface.hostLeaves()

        assertEquals(listOf("ready", "unmount"), h.log)
    }

    @Test
    fun `the host leaving while the sheet may be up reports an unknown outcome`() {
        val h = Harness().presented()

        h.surface.hostLeaves()

        assertEquals(listOf("ready", "unmount", "error:PAYMENT_OUTCOME_UNKNOWN"), h.log)
        assertTrue(h.errors.single().attemptMayExist)
    }

    @Test
    fun `the host leaving after submission keeps the page until it settles`() {
        val h = Harness().presented()
        h.send("onramp_api.commit_success")

        h.surface.hostLeaves()
        assertEquals(1, h.surfaces.count)
        h.send("onramp_api.polling_success")

        assertEquals(0, h.surfaces.count)
        assertEquals(listOf("ready", "status:pending", "submitted", "unmount"), h.log)
    }

    @Test
    fun `the shipped timing is twenty presses a quarter second apart within the iOS bounds`() {
        val timing = HostedWalletSession.Timing()
        assertEquals(20_000, timing.loadTimeoutMs)
        assertEquals(250, timing.pressIntervalMs)
        assertEquals(20, timing.pressAttempts)
        assertEquals(8_000, timing.presentationDeadlineMs)
        assertEquals(180_000, timing.presentationCeilingMs)
        assertEquals(60_000, timing.detachedGraceMs)
    }

    private companion object {
        val PROFILE = HostedLinkProfiles.all.single()
        val SCRIPT = PROFILE.presentScripts.getValue("GOOGLE_PAY")
        const val HANG = "hang"
        val FAST = HostedWalletSession.Timing(
            loadTimeoutMs = 1_000,
            pressIntervalMs = 10,
            pressAttempts = 3,
            pressTimeoutMs = 200,
            presentationDeadlineMs = 500,
            presentationCeilingMs = 2_000,
            detachedGraceMs = 5_000,
        )

        fun message(eventName: String, data: String = "{}"): Map<String, Any?> =
            mapOf("channel" to PROFILE.channel, "body" to """{"eventName":"$eventName","data":$data}""")
    }
}
