package io.meld.sdk.hosted

/** A [Scheduler] on a virtual clock: work runs only when a test advances it. */
internal class VirtualClock : Scheduler {
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
