package io.meld.sdk.requirements

import io.meld.sdk.actions.ActionConnector
import io.meld.sdk.actions.FakeConnection
import io.meld.sdk.hosted.Cancellable
import io.meld.sdk.toMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** The shared fixtures in `requirements-fixtures/`, read from the test classpath. */
internal object RequirementsFixtures {
    const val ROOT = "requirements-fixtures"

    fun bytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$ROOT/$path")) { "missing fixture $path" }.use { it.readBytes() }

    fun json(path: String): Map<String, Any?> = JSONObject(String(bytes(path), Charsets.UTF_8)).toMap()

    /** Every `requirements-session/` fixture by name, read from the directory, so a new one needs no code change. */
    fun sessionNames(): List<String> =
        File(checkNotNull(javaClass.getResource("/$ROOT/requirements-session")).toURI()).listFiles().orEmpty()
            .map { it.name }.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
}

/** One `requirements-session/` fixture: the mint response and its exchanges, in file order. */
@Suppress("UNCHECKED_CAST")
internal class SessionFixture(val name: String) {
    val raw = RequirementsFixtures.json("requirements-session/$name.json")
    val mint = (raw["mint"] as Map<String, Any?>)["response"] as Map<String, Any?>
    val exchanges = raw["exchanges"] as List<Map<String, Any?>>
    val session: MeldRequirementsSession get() = MeldRequirementsSession.fromMap(mint)
    val endpoint: String get() = "https://api-sb.meld.io" + (mint["requirementsActions"] as Map<String, Any?>)["endpoint"]
    val bearer: String get() = "Bearer ${mint["token"]}"

    fun exchange(index: Int) = exchanges[index]

    fun response(index: Int) = exchange(index)["response"] as Map<String, Any?>

    fun key(index: Int): UUID = UUID.fromString(exchange(index)["idempotencyKey"] as String)
}

/** Answers each POST with the next scripted answer and records what was sent. Never touches a network. */
internal class ScriptedServer {
    private class Answer(val status: Int, val body: ByteArray?, val failAt: String?, val onRead: () -> Unit)

    private val answers = ArrayDeque<Answer>()
    val sent = mutableListOf<FakeConnection>()

    val connector = ActionConnector { url ->
        val answer = answers.removeFirstOrNull() ?: throw AssertionError("unexpected request ${sent.size + 1} to $url")
        FakeConnection(url, answer.status, answer.body, answer.failAt, answer.onRead).also { sent += it }
    }

    val pending: Int get() = answers.size

    fun answer(status: Int, body: Any?, failAt: String? = null, onRead: () -> Unit = {}) {
        val bytes = when (body) {
            null -> null
            is String -> body.toByteArray()
            is Map<*, *> -> jsonOf(body).toString().toByteArray()
            else -> error("unsupported body")
        }
        answers += Answer(status, bytes, failAt, onRead)
    }

    /** Answers with a fixture exchange's own status and response. */
    fun answer(exchange: Map<String, Any?>) = answer(exchange["status"] as Int, exchange["response"])

    fun fail(at: String = "connect") = answer(200, null, failAt = at)

    fun body(index: Int): Map<String, Any?> = JSONObject(String(sent[index].sent.toByteArray(), Charsets.UTF_8)).toMap()

    fun key(index: Int): String? = sent[index].headers["X-Idempotency-Key"]

    /** Request [index] is exactly the fixture exchange's request, with its key and the session's bearer. */
    fun assertSent(index: Int, fixture: SessionFixture, exchange: Map<String, Any?>) {
        assertEquals(fixture.endpoint, sent[index].url.toString())
        assertEquals(fixture.bearer, sent[index].headers["Authorization"])
        assertEquals(exchange["request"], body(index))
        assertEquals(exchange["idempotencyKey"] as String?, key(index))
    }
}

/**
 * Answers as the server would have for [fixture]. A READ is the fixture's next READ when that is next, else the
 * last READ without the steps a RUN_STEP has finished since. A RUN_STEP must equal an unused fixture request;
 * one the fixture does not script is kept in [unscripted] and [onUnscripted] runs: the fixture stops there.
 */
@Suppress("UNCHECKED_CAST")
internal class ReplayServer(val fixture: SessionFixture) {
    private val used = BooleanArray(fixture.exchanges.size)
    /** The READ the loop was last answered. */
    var current: Map<String, Any?>? = null
        private set
    private val finished = mutableSetOf<String>()
    val sent = mutableListOf<AnsweringConnection>()
    val statuses = mutableListOf<Int>()
    val unscripted = mutableListOf<Map<String, Any?>>()
    var onUnscripted: () -> Unit = {}

    val connector = ActionConnector { url -> AnsweringConnection(url, ::answer).also { sent += it } }

    val unused: List<Map<String, Any?>> get() = fixture.exchanges.filterIndexed { index, _ -> !used[index] }

    fun body(index: Int): Map<String, Any?> = JSONObject(String(sent[index].sent.toByteArray(), Charsets.UTF_8)).toMap()

    /** The values the fixture sent next for [stepId], or null when it sent none. */
    fun scriptedFields(stepId: String): Map<String, String>? = fixture.exchanges.indices
        .firstOrNull { !used[it] && isRun(it) && request(it)["stepId"] == stepId && request(it)["fields"] != null }
        ?.let { request(it)["fields"] as Map<String, String> }

    private fun answer(sent: ByteArray): Pair<Int, ByteArray?> {
        val body = JSONObject(String(sent, Charsets.UTF_8)).toMap()
        val (status, response) = if (body["operation"] == "READ") read() else run(body)
        statuses += status
        return status to response?.let { jsonOf(it).toString().toByteArray() }
    }

    private fun read(): Pair<Int, Map<String, Any?>> {
        val next = used.indexOfFirst { !it }
        if (next >= 0 && fixture.exchanges[next]["operation"] == "READ") {
            used[next] = true
            val status = fixture.exchanges[next]["status"] as Int
            if (status == 200) {
                finished.clear()
                current = fixture.response(next)
            }
            return status to fixture.response(next)
        }
        val last = checkNotNull(current) { "${fixture.name}: no READ to repeat" }
        return 200 to last + ("next" to (last["next"] as List<Map<String, Any?>>).filter { it["stepId"] !in finished })
    }

    private fun run(body: Map<String, Any?>): Pair<Int, Map<String, Any?>?> {
        val match = fixture.exchanges.indices.firstOrNull { !used[it] && isRun(it) && request(it) == body }
        if (match == null) {
            unscripted += body
            onUnscripted()
            return 500 to null
        }
        used[match] = true
        val status = fixture.exchanges[match]["status"] as Int
        val response = fixture.response(match)
        if (status == 200 && response["outcome"] == "DONE") finished += body["stepId"] as String
        return status to response
    }

    private fun isRun(index: Int) = fixture.exchanges[index]["operation"] == "RUN_STEP"

    private fun request(index: Int) = fixture.exchanges[index]["request"] as Map<String, Any?>
}

/** An [HttpURLConnection] whose answer depends on the body it was sent. Never touches a network. */
internal class AnsweringConnection(url: URL, private val respond: (ByteArray) -> Pair<Int, ByteArray?>) : HttpURLConnection(url) {
    val headers = linkedMapOf<String, String>()
    val sent = ByteArrayOutputStream()
    private val answer by lazy { respond(sent.toByteArray()) }

    override fun connect() {
        connected = true
    }

    override fun disconnect() = Unit

    override fun usingProxy() = false

    override fun setRequestProperty(key: String, value: String) {
        headers[key] = value
        super.setRequestProperty(key, value)
    }

    override fun getOutputStream(): OutputStream {
        connect()
        return sent
    }

    override fun getResponseCode(): Int = answer.first

    override fun getInputStream(): InputStream {
        if (answer.first !in 200..299) throw FileNotFoundException("synthetic")
        return ByteArrayInputStream(answer.second ?: throw IOException("no body"))
    }

    override fun getErrorStream(): InputStream? = answer.second?.takeIf { answer.first >= 400 }?.let(::ByteArrayInputStream)
}

/** [value] as org.json, keeping null members, which `JSONObject(Map)` drops. */
internal fun jsonOf(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { obj -> value.forEach { (key, entry) -> obj.put(key as String, jsonOf(entry)) } }
    is List<*> -> JSONArray().also { array -> value.forEach { array.put(jsonOf(it)) } }
    else -> value
}

/** A presenter whose page the test closes, on a host the test resumes. */
internal class FakePresenter(
    override var available: Boolean = true,
    var opens: Boolean = true,
    var resumed: Boolean = true,
) : HostedPagePresenter {
    val presented = mutableListOf<String>()
    private var onClosed: (() -> Unit)? = null
    private var onResumed: (() -> Unit)? = null
    var dismissed = 0
        private set

    val showing: Boolean get() = onClosed != null

    val waitingForResume: Boolean get() = onResumed != null

    override fun whenResumed(then: () -> Unit): Cancellable? {
        if (resumed) return null
        onResumed = then
        return Cancellable { onResumed = null }
    }

    fun resume() {
        resumed = true
        onResumed?.also { onResumed = null }?.invoke()
    }

    override fun present(url: String, onClosed: () -> Unit): Cancellable? {
        if (!opens) return null
        presented += url
        this.onClosed = onClosed
        return Cancellable {
            dismissed += 1
            this.onClosed = null
        }
    }

    fun close() {
        val closed = checkNotNull(onClosed) { "no page is showing" }
        onClosed = null
        closed()
    }
}

/**
 * A READ's providers as [MeldReadyResult.providers] reports them: `open` is every PRE_ORDER requirement that
 * is not SATISFIED, with its `reason` and a non-empty `errorCodes`.
 */
@Suppress("UNCHECKED_CAST")
internal fun reportedProviders(read: Map<String, Any?>): List<MeldReadyProvider> =
    (read["providers"] as List<Map<String, Any?>>).map { provider ->
        val open = (provider["requirements"] as List<Map<String, Any?>>? ?: emptyList())
            .filter { it["timing"] == "PRE_ORDER" && it["status"] != "SATISFIED" }
            .map {
                MeldReadyOpenStep(it["type"] as String, it["code"] as String, it["status"] as String, it["reason"] as String?,
                    (it["errorCodes"] as List<String>?)?.takeIf { codes -> codes.isNotEmpty() })
            }
        MeldReadyProvider(provider["serviceProvider"] as String, provider["status"] as String, provider["eligible"] as Boolean?, open)
    }

/** [snapshot] with its `next` replaced, the server's state after a step the fixtures do not record. */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.withNext(vararg stepIds: String): Map<String, Any?> {
    val next = this["next"] as List<Map<String, Any?>>
    return this + ("next" to stepIds.map { id -> next.single { it["stepId"] == id } })
}
