package io.meld.sdk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Meld API client used to advance a two-step provider flow (Option 1) — e.g. Uphold cards,
 * where the SDK must call Meld after capturing the card to create the authorize session. The capture
 * order supplies the exact [authorizeSessionUrl] and a short-lived [continuationToken] (bearer), so
 * the SDK needs no integrator credentials or base-URL configuration.
 *
 * Blocking — call OFF the main thread. Returns the authorize [MeldOrder] to mount next.
 */
internal object MeldApiClient {

    /** POST { cardId } to the order's authorize-session endpoint; returns the authorize order. */
    fun createAuthorizeSession(
        authorizeSessionUrl: String,
        continuationToken: String?,
        cardId: String,
    ): MeldOrder {
        val connection = (URL(authorizeSessionUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!continuationToken.isNullOrEmpty()) {
                setRequestProperty("Authorization", "Bearer $continuationToken")
            }
        }
        try {
            connection.outputStream.use { out ->
                out.write(JSONObject().put("cardId", cardId).toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw MeldMountException.Unsupported("authorize-session request failed ($code): $body")
            }
            return MeldOrder.fromJson(body)
        } finally {
            connection.disconnect()
        }
    }
}
