package io.meld.sdk.actions

import io.meld.sdk.hosted.VirtualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ActionQueueTest {
    private fun ExecutorService.settle() {
        shutdown()
        assertTrue(awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun work_runs_in_submission_order_on_one_daemon_thread_named_meld_actions() {
        val executor = ActionQueue.newActionsExecutor()
        val main = QueuedMain()
        val queue = ActionQueue(executor, main)
        val ran = Collections.synchronizedList(mutableListOf<Pair<Int, Thread>>())
        val delivered = mutableListOf<Int>()

        for (index in 0 until 100) queue.run({ ran += index to Thread.currentThread(); index }) { delivered += it.getOrThrow() }
        executor.settle()

        assertEquals((0 until 100).toList(), ran.map { it.first })
        val thread = ran.map { it.second }.distinct().single()
        assertEquals("meld-actions", thread.name)
        assertTrue(thread.isDaemon)
        assertTrue(thread !== Thread.currentThread())
        assertEquals(emptyList<Int>(), delivered)
        assertEquals(100, main.drain())
        assertEquals((0 until 100).toList(), delivered)
    }

    @Test fun results_arrive_only_through_main_and_failures_keep_their_exception() {
        val clock = VirtualClock()
        val queue = ActionQueue(DirectExecutor(), clock)
        val results = mutableListOf<Result<String>>()
        queue.run({ "answer" }) { results += it }
        queue.run({ throw PaymentActionException.Transport }) { results += it }
        assertEquals(0, results.size)

        clock.advance()

        assertEquals("answer", results[0].getOrThrow())
        assertSame(PaymentActionException.Transport, results[1].exceptionOrNull())
    }

    @Test fun finish_lets_running_and_queued_work_complete_but_delivers_nothing() {
        val executor = ActionQueue.newActionsExecutor()
        val main = QueuedMain()
        val queue = ActionQueue(executor, main)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val delivered = mutableListOf<String>()

        queue.run({ started.countDown(); release.await(); effects += "in flight" }) { delivered += "in flight" }
        queue.run({ effects += "queued" }) { delivered += "queued" }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        queue.finish()
        queue.run({ effects += "after finish" }) { delivered += "after finish" }
        release.countDown()
        executor.settle()

        assertEquals(listOf("in flight", "queued"), effects)
        assertEquals(0, main.drain())
        assertEquals(emptyList<String>(), delivered)
    }

    @Test fun a_result_already_posted_to_main_is_dropped_by_a_later_finish() {
        val clock = VirtualClock()
        val queue = ActionQueue(DirectExecutor(), clock)
        val delivered = mutableListOf<Int>()
        queue.run({ 1 }) { delivered += it.getOrThrow() }
        queue.finish()
        clock.advance()
        assertEquals(emptyList<Int>(), delivered)
    }

    @Test fun a_sent_action_is_answered_on_main_and_a_finished_client_still_reads_the_response() {
        val executor = ActionQueue.newActionsExecutor()
        val main = QueuedMain()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connections = Collections.synchronizedList(mutableListOf<FakeConnection>())
        var blocking = false
        val client = PaymentActionClient(ActionFixtures.descriptor(), ActionQueue(executor, main)) { url ->
            val block = blocking
            FakeConnection(url, onRead = { if (block) { reading.countDown(); release.await() } }).also { connections += it }
        }
        val answers = mutableListOf<Result<Map<String, Any?>>>()

        client.send("READ_SUBMISSION") { answers += it }
        assertTrue(main.awaitPosted())
        assertEquals(1, connections[0].disconnected)
        assertEquals(0, answers.size)
        assertEquals(1, main.drain())
        assertEquals(mapOf("version" to 1), answers.single().getOrThrow())

        blocking = true
        client.send("SUBMIT_WALLET_PAYMENT", key = java.util.UUID.randomUUID()) { answers += it }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        client.finish()
        release.countDown()
        executor.settle()

        assertTrue(connections[1].bytesRead > 0)
        assertEquals(1, connections[1].disconnected)
        assertEquals(0, main.drain())
        assertEquals(1, answers.size)
    }

    @Test fun an_invalid_send_is_answered_through_main_too() {
        val clock = VirtualClock()
        val client = PaymentActionClient(ActionFixtures.descriptor(), ActionQueue(DirectExecutor(), clock)) { error("no connection") }
        val answers = mutableListOf<Result<Map<String, Any?>>>()
        client.send("UNKNOWN") { answers += it }
        assertEquals(0, answers.size)
        clock.advance()
        assertSame(PaymentActionException.InvalidRequest, answers.single().exceptionOrNull())
    }
}
