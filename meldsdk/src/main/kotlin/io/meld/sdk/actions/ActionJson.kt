package io.meld.sdk.actions

import io.meld.sdk.toMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The largest action request or response body, in bytes: the server's own limit. */
internal const val MAX_ACTION_BODY_BYTES = 65_536

/**
 * An exact integer, whatever number class decoded it: the JVM and Android org.json differ, and React
 * Native hands every number over as a double. Booleans, strings and fractional values are not integers.
 */
internal fun strictInt(value: Any?): Int? {
    val number = value as? Number ?: return null
    return try {
        number.toString().toBigDecimal().intValueExact()
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

/** A JSON boolean, never a number or string that reads like one. */
internal fun strictBoolean(value: Any?): Boolean? = value as? Boolean

/**
 * Resolves an RFC 6901 pointer against [root], or null when it does not resolve. Only `~0` and `~1`
 * escapes are allowed, and an array index must be canonical, so `/items/01` never reads item 1.
 */
internal fun jsonPointer(root: Map<String, Any?>, pointer: String): Any? {
    if (!pointer.startsWith("/") || pointer.toByteArray(Charsets.UTF_8).size > MAX_POINTER_BYTES) return null
    var current: Any? = root
    for (encoded in pointer.substring(1).split("/")) {
        if (INVALID_ESCAPE.containsMatchIn(encoded)) return null
        val key = encoded.replace("~1", "/").replace("~0", "~")
        current = when (val node = current) {
            is Map<*, *> -> if (node.containsKey(key)) node[key] else return null
            is List<*> -> {
                val index = key.toIntOrNull()?.takeIf { it >= 0 && it.toString() == key } ?: return null
                node.getOrNull(index) ?: return null
            }
            else -> return null
        }
    }
    return current
}

/** A JSON object body of at most [MAX_ACTION_BODY_BYTES], as Kotlin collections; null if it is anything else. */
internal fun decodeActionObject(body: ByteArray): Map<String, Any?>? {
    if (body.size > MAX_ACTION_BODY_BYTES) return null
    return try {
        JSONObject(String(body, Charsets.UTF_8)).toMap()
    } catch (_: JSONException) {
        null
    }
}

/**
 * [fields] as a JSON object, dropping null entries as a Swift dictionary would. Anything that is not
 * JSON, such as a non-string key, a non-finite number or another class, is [PaymentActionException.InvalidRequest].
 */
internal fun encodeActionObject(fields: Map<String, Any?>): ByteArray = try {
    json(fields).toString().toByteArray(Charsets.UTF_8)
} catch (_: JSONException) {
    throw PaymentActionException.InvalidRequest
}

private fun json(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is String, is Boolean -> value
    is Number -> value.also { if (!value.toDouble().isFinite()) throw PaymentActionException.InvalidRequest }
    is Map<*, *> -> JSONObject().also { obj ->
        for ((key, entry) in value) {
            if (key !is String) throw PaymentActionException.InvalidRequest
            if (entry != null) obj.put(key, json(entry))
        }
    }
    is List<*> -> JSONArray().also { array -> value.forEach { array.put(json(it)) } }
    else -> throw PaymentActionException.InvalidRequest
}

private const val MAX_POINTER_BYTES = 1024
private val INVALID_ESCAPE = Regex("~(?:[^01]|$)")
