package io.meld.sdk.requirements

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedPagePresenterTest {
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry

        fun leaveAndReturn() {
            for (event in listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)) {
                registry.handleLifecycleEvent(event)
            }
        }
    }

    private class Launcher(var canOpen: Boolean = true, var opens: Boolean = true) : BrowserLauncher {
        val opened = mutableListOf<String>()

        override fun canOpen() = canOpen

        override fun open(url: String) = opens.also { if (it) opened += url }
    }

    private val url = "https://onboarding.noah.test/hosted?session=synthetic"

    @Test fun the_page_counts_as_closed_at_the_first_resume_after_the_host_paused_for_it() {
        val host = Host()
        val launcher = Launcher()
        var closed = 0
        assertNotNull(BrowserPresenter(host.lifecycle, launcher).present(url) { closed += 1 })
        assertEquals(listOf(url), launcher.opened)
        assertEquals("adding the watcher to a resumed host replays a resume", 0, closed)

        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertEquals(0, closed)
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(1, closed)
        host.leaveAndReturn()
        assertEquals(1, closed)
        assertEquals(0, host.registry.observerCount)
    }

    @Test fun on_a_host_that_is_not_resumed_the_page_counts_as_closed_at_its_next_resume() {
        for (away in listOf(listOf(Lifecycle.Event.ON_PAUSE), listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP))) {
            val host = Host()
            away.forEach(host.registry::handleLifecycleEvent)
            var closed = 0
            assertNotNull(BrowserPresenter(host.lifecycle, Launcher()).present(url) { closed += 1 })
            if (Lifecycle.Event.ON_STOP in away) host.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertEquals("$away", 0, closed)
            host.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertEquals("$away", 1, closed)
            host.leaveAndReturn()
            assertEquals("$away", 1, closed)
            assertEquals("$away", 0, host.registry.observerCount)
        }
    }

    @Test fun when_resumed_runs_at_once_on_a_resumed_host_and_otherwise_at_its_next_resume() {
        val host = Host()
        val presenter = BrowserPresenter(host.lifecycle, Launcher())
        var resumed = 0
        assertNull(presenter.whenResumed { resumed += 1 })
        assertEquals(0, host.registry.observerCount)

        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertNotNull(presenter.whenResumed { resumed += 1 })
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(0, resumed)
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(1, resumed)
        host.leaveAndReturn()
        assertEquals(1, resumed)
        assertEquals(0, host.registry.observerCount)

        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        checkNotNull(presenter.whenResumed { resumed += 1 }).cancel()
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(1, resumed)
        assertEquals(0, host.registry.observerCount)
    }

    @Test fun a_page_that_did_not_open_reports_nothing_and_leaves_nothing_behind() {
        val host = Host()
        var closed = 0
        assertNull(BrowserPresenter(host.lifecycle, Launcher(opens = false)).present(url) { closed += 1 })
        host.leaveAndReturn()
        assertEquals(0, closed)
        assertEquals(0, host.registry.observerCount)
    }

    @Test fun a_cancelled_or_destroyed_presentation_never_reports() {
        val host = Host()
        var closed = 0
        val presentation = checkNotNull(BrowserPresenter(host.lifecycle, Launcher()).present(url) { closed += 1 })
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        presentation.cancel()
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(0, closed)
        assertEquals(0, host.registry.observerCount)

        BrowserPresenter(host.lifecycle, Launcher()).present(url) { closed += 1 }
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertEquals(0, closed)
        assertEquals(0, host.registry.observerCount)
    }

    @Test fun the_presenter_is_available_exactly_when_a_browser_can_open_https() {
        val launcher = Launcher(canOpen = false)
        val presenter = BrowserPresenter(Host().lifecycle, launcher)
        assertFalse(presenter.available)
        launcher.canOpen = true
        assertTrue(presenter.available)
    }

    @Test fun only_https_pages_with_a_host_and_no_credentials_open() {
        for (page in listOf(url, "https://in.sumsub.com/websdk/p/x", "https://pay.coinbase.test:443/limits")) {
            assertTrue(page, isHttpsPage(page))
        }
        for (page in listOf("http://in.sumsub.com/x", "HTTPS://in.sumsub.com/x", "https:///x", "https://user:pass@in.sumsub.com/x",
            "javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "in.sumsub.com/x", "https://in.sumsub.com/x y", "")) {
            assertFalse(page, isHttpsPage(page))
        }
    }
}
