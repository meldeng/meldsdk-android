package io.meld.sdk.actions

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Opens the connection for one action. A seam, so JVM tests answer without a network. */
internal fun interface ActionConnector {
    @Throws(IOException::class)
    fun open(url: URL): HttpURLConnection
}

/** Sends one declared action and returns its versioned JSON answer. Blocks, so never call it on main. */
internal fun interface PaymentActionTransport {
    fun execute(operation: String, fields: Map<String, Any?>, key: UUID?): Map<String, Any?>
}

/** One order's actions and the ordered thread they run on, as iOS's `PaymentActionSending`. */
internal interface PaymentActionSending : PaymentActionTransport {
    /** Runs [job] on the actions thread after all earlier work. [onResult] runs on main, and never after [finish]. */
    fun <T> run(job: () -> T, onResult: (Result<T>) -> Unit)

    /** Sends [operation] on the actions thread. [callback] runs on main, and never after [finish]. */
    fun send(
        operation: String,
        fields: Map<String, Any?> = emptyMap(),
        key: UUID? = null,
        callback: (Result<Map<String, Any?>>) -> Unit,
    ) = run({ execute(operation, fields, key) }, callback)

    /** Lets work in flight complete and accepts none after it. */
    fun finish()
}

/**
 * Order-scoped transport over the platform [HttpURLConnection]: no cache, no redirects, no response
 * diagnostics and no application retries. The platform may retry a failed connection by itself, which is
 * safe because every mutation carries an idempotency key and a concurrent duplicate is answered 425.
 * Unlike iOS, a `CookieHandler` the host app installed is still consulted; the endpoint sets no cookies
 * and authorizes only the bearer.
 */
internal class PaymentActionClient(
    val descriptor: PaymentActionDescriptor,
    private val queue: ActionQueue = ActionQueue(),
    private val connector: ActionConnector = ActionConnector { url -> url.openConnection() as HttpURLConnection },
) : PaymentActionSending {

    override fun <T> run(job: () -> T, onResult: (Result<T>) -> Unit) = queue.run(job, onResult)

    override fun finish() = queue.finish()

    override fun execute(operation: String, fields: Map<String, Any?>, key: UUID?): Map<String, Any?> {
        val request = descriptor.request(operation, fields, key)
        val connection = try {
            connector.open(request.url.toURL())
        } catch (_: IOException) {
            throw PaymentActionException.Transport
        } catch (_: RuntimeException) {
            throw PaymentActionException.Transport
        }
        try {
            return exchange(connection, request)
        } catch (_: IOException) {
            throw PaymentActionException.Transport
        } catch (_: RuntimeException) {
            throw PaymentActionException.Transport
        } finally {
            connection.disconnect()
        }
    }

    private fun exchange(connection: HttpURLConnection, request: ActionRequest): Map<String, Any?> {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setFixedLengthStreamingMode(request.body.size)
        for ((name, value) in request.headers) connection.setRequestProperty(name, value)
        connection.outputStream.use { it.write(request.body) }

        val status = connection.responseCode
        if (status < 100) throw PaymentActionException.InvalidResponse
        val success = status in 200..299
        val body = (if (success) connection.inputStream else connection.errorStream)?.use(::readBounded)
        if (!success) throw PaymentActionFailureCode.decode(body, status)
        val json = body?.let(::decodeActionObject)
        if (json == null || strictInt(json["version"]) != 1) throw PaymentActionException.InvalidResponse
        return json
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        /** One byte past the limit, so an oversized body is seen as oversized without reading the rest. */
        fun readBounded(stream: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (out.size() <= MAX_ACTION_BODY_BYTES) {
                val read = stream.read(buffer, 0, minOf(buffer.size, MAX_ACTION_BODY_BYTES + 1 - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
