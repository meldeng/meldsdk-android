package io.meld.sdk.actions

import android.os.Handler
import android.os.Looper
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldOrder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal sealed class PaymentActionError(message: String) : Exception(message) {
    class InvalidRequest : PaymentActionError("invalid_request")

    /** [sent] is false only when the connection failed before any of the request was written. */
    class Transport(val sent: Boolean) : PaymentActionError(if (sent) "transport_after_send" else "transport")
}

internal class PaymentActionRequest(val url: URL, val headers: Map<String, String>, val body: ByteArray) {
    override fun toString(): String = "PaymentActionRequest[REDACTED]"
}

internal class PaymentActionResponse(val status: Int, val body: String?)

/** The order's `paymentActions`, validated against the configured environment's API host. */
internal class PaymentActionDescriptor private constructor(
    val endpoint: URL,
    private val bearer: String,
    val operations: Map<String, Boolean>,
) {
    override fun toString(): String = "PaymentActionDescriptor[REDACTED]"

    fun request(operation: String, fields: Map<String, Any?>, idempotencyKey: UUID?): PaymentActionRequest {
        val required = operations[operation]
        if (required == null || required != (idempotencyKey != null) || "version" in fields || "operation" in fields) {
            throw PaymentActionError.InvalidRequest()
        }
        val body = JSONObject(fields + mapOf("version" to 1, "operation" to operation)).toString().toByteArray(Charsets.UTF_8)
        if (body.size > BODY_LIMIT) throw PaymentActionError.InvalidRequest()
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Cache-Control" to "no-store",
            "Authorization" to "Bearer $bearer",
        )
        if (idempotencyKey != null) headers["X-Idempotency-Key"] = idempotencyKey.toString()
        return PaymentActionRequest(endpoint, headers, body)
    }

    companion object {
        const val BODY_LIMIT = 65_536
        private val ACTION_NAME = Regex("[A-Z][A-Z0-9_]{0,63}")
        private val INVALID_ESCAPE = Regex("~(?:[^01]|$)")
        private val ARRAY_INDEX = Regex("0|[1-9][0-9]*")

        fun baseUrl(environment: MeldEnvironment): String = when (environment) {
            MeldEnvironment.SANDBOX -> "https://api-sb.meld.io"
            MeldEnvironment.QA -> "https://api-qa.meld.io"
            MeldEnvironment.PRODUCTION -> "https://api.meld.io"
        }

        fun parse(order: MeldOrder, environment: MeldEnvironment): PaymentActionDescriptor? {
            val raw = order.raw["paymentActions"] as? Map<*, *> ?: return null
            val orderId = order.id?.takeIf { it.isNotEmpty() } ?: return null
            val declared = raw["endpoint"] as? String ?: return null
            if (!isOne(raw["version"])) return null
            val base = URI(baseUrl(environment))
            val url = try {
                base.resolve(declared)
            } catch (_: IllegalArgumentException) {
                return null
            }
            // Comparing the declared string with its resolved form rejects userinfo, ports, queries,
            // fragments, dot segments and escapes in one place.
            val path = url.rawPath ?: return null
            if (url.scheme != "https" || url.host != base.host || url.port != -1 || path != url.path ||
                (declared != path && declared != "https://${base.host}$path")
            ) {
                return null
            }
            val parts = path.split("/")
            if (parts.size != 8 || parts.subList(0, 4) != listOf("", "crypto", "order", "headless") ||
                parts[4] !in setOf("onramp", "offramp") ||
                !ACTION_NAME.matches(parts[5]) || parts[6] != orderId || parts[7] != "actions" ||
                (order.serviceProvider != null && order.serviceProvider != parts[5])
            ) {
                return null
            }
            val pointer = raw["bearerTokenPointer"] as? String ?: return null
            val bearer = resolve(pointer, order.raw) as? String ?: return null
            if (bearer.isEmpty() || bearer.length > 16_384 || bearer.any { it.code !in 0x21..0x7e }) return null
            val declarations = raw["operations"] as? List<*> ?: return null
            if (declarations.isEmpty()) return null
            val operations = LinkedHashMap<String, Boolean>()
            for (declaration in declarations) {
                val item = declaration as? Map<*, *> ?: return null
                val operation = item["operation"] as? String ?: return null
                val required = item["idempotencyKeyRequired"] as? Boolean ?: return null
                if (!ACTION_NAME.matches(operation) || operations.put(operation, required) != null) return null
            }
            val endpoint = try {
                url.toURL()
            } catch (_: Exception) {
                return null
            }
            return PaymentActionDescriptor(endpoint, bearer, operations)
        }

        /** RFC 6901. */
        fun resolve(pointer: String, root: Any?): Any? {
            if (!pointer.startsWith("/") || pointer.length > 1024) return null
            var current = root
            for (encoded in pointer.substring(1).split("/")) {
                if (INVALID_ESCAPE.containsMatchIn(encoded)) return null
                val key = encoded.replace("~1", "/").replace("~0", "~")
                current = when (current) {
                    is Map<*, *> -> if (current.containsKey(key)) current[key] else return null
                    is List<*> -> {
                        val index = key.takeIf { ARRAY_INDEX.matches(it) }?.toIntOrNull() ?: return null
                        if (index >= current.size) return null
                        current[index]
                    }
                    else -> return null
                }
            }
            return current
        }
    }
}

internal fun interface PaymentActionTransport {
    /** Blocking. Throws [PaymentActionError.Transport]; a body over the limit arrives as null. */
    fun post(request: PaymentActionRequest): PaymentActionResponse
}

/** No cache, no redirects, bounded bodies. */
internal object HttpPaymentActionTransport : PaymentActionTransport {
    override fun post(request: PaymentActionRequest): PaymentActionResponse {
        val connection = try {
            request.url.openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw PaymentActionError.Transport(sent = false)
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setFixedLengthStreamingMode(request.body.size)
            request.headers.forEach(connection::setRequestProperty)
            try {
                connection.connect()
            } catch (_: IOException) {
                throw PaymentActionError.Transport(sent = false)
            }
            try {
                connection.outputStream.use { it.write(request.body) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                return PaymentActionResponse(status, stream?.use(::readBounded))
            } catch (_: IOException) {
                throw PaymentActionError.Transport(sent = true)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > PaymentActionDescriptor.BODY_LIMIT) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

/**
 * Order-scoped relay for `paymentActions`. It sends only an operation the order declares, to the
 * order's own endpoint with the order's bearer, and hands back the raw response for the caller to
 * decode. It never interprets provider state.
 */
internal class PaymentActionClient(
    private val descriptor: PaymentActionDescriptor,
    private val transport: PaymentActionTransport = HttpPaymentActionTransport,
) {
    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "MeldPaymentActions").apply { isDaemon = true } }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Completes on the main thread with the response, or null when there is none. */
    fun send(operation: String, fields: Map<String, Any?>, idempotencyKey: UUID?, completion: (PaymentActionResponse?) -> Unit) {
        try {
            executor.execute {
                val response = perform(operation, fields, idempotencyKey)
                main.post { completion(response) }
            }
        } catch (_: RejectedExecutionException) {
            // Closed: the session that asked is gone.
        }
    }

    fun close() {
        executor.shutdown()
    }

    fun perform(operation: String, fields: Map<String, Any?>, idempotencyKey: UUID?): PaymentActionResponse? {
        val request = try {
            descriptor.request(operation, fields, idempotencyKey)
        } catch (_: Exception) {
            return null
        }
        return try {
            transport.post(request)
        } catch (_: Exception) {
            null
        }
    }
}

internal fun isOne(value: Any?): Boolean = value is Number && value.toDouble() == 1.0
