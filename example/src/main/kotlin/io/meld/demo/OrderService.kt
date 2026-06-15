package io.meld.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// ⚠️ POC ONLY — DO NOT SHIP.
// This file talks to the Meld API directly, which puts your API key in the app binary. In a real
// app, quote/order creation happens on YOUR backend (the key never reaches the client); the app
// just receives the order JSON and hands it to MeldSDK. The SDK itself is frontend-only and never
// sees the key.

object DemoConfig {
    // Credentials come from local.properties (via BuildConfig) or an env var of the same name.
    val meldApiKey: String get() = BuildConfig.MELD_API_KEY
    val meldCustomerId: String get() = BuildConfig.MELD_CUSTOMER_ID

    /** API host. Defaults to sandbox; set MELD_API_HOST (e.g. `api-qa.meld.io`) for another env. */
    val apiBase: String
        get() = "https://" + BuildConfig.MELD_API_HOST.ifEmpty { "api-sb.meld.io" }

    const val VERSION = "2026-05-01"

    // Fixed corridor for the demo: 15 USD -> BTC, US, Mercuryo card.
    const val SOURCE_AMOUNT = "15"
    const val SOURCE_CURRENCY = "USD"
    const val DESTINATION_CURRENCY = "BTC"
    const val COUNTRY = "US"
    const val DEFAULT_WALLET = "bc1qr74wmrcwqq9w5yxczxj6udts9mnqsh3xlhk5yp"
}

data class DemoQuote(val destinationAmount: Double?, val totalFee: Double?, val exchangeRate: Double?)

class DemoException(message: String) : Exception(message)

/** Backend calls — in a real app, these live on your server. */
class OrderService {

    /**
     * The order's `clientIpAddress` must match the IP the WebView egresses on — Mercuryo binds the
     * widget signature to it — so discover the device's public IP and pass it on the order.
     */
    suspend fun publicIP(): String? = withContext(Dispatchers.IO) {
        for (host in listOf("https://api64.ipify.org?format=json", "https://api.ipify.org?format=json")) {
            try {
                val ip = JSONObject(URL(host).readText()).optString("ip")
                if (ip.isNotEmpty()) return@withContext ip
            } catch (e: Exception) {
                // try next host
            }
        }
        null
    }

    /** `POST /payments/crypto/quote?integrationMode=HEADLESS` — the live quote for the corridor. */
    suspend fun quote(): DemoQuote = withContext(Dispatchers.IO) {
        val body = JSONObject(
            mapOf(
                "countryCode" to DemoConfig.COUNTRY,
                "sourceAmount" to DemoConfig.SOURCE_AMOUNT,
                "sourceCurrencyCode" to DemoConfig.SOURCE_CURRENCY,
                "destinationCurrencyCode" to DemoConfig.DESTINATION_CURRENCY,
                "paymentMethodType" to "CREDIT_DEBIT_CARD",
                "serviceProviders" to JSONArray(listOf("MERCURYO")),
            ),
        )
        val (status, data) = post("/payments/crypto/quote?integrationMode=HEADLESS", body)
        val json = JSONObject(data)
        val quotes = json.optJSONArray("quotes")
        if (status !in 200..299 || quotes == null || quotes.length() == 0) {
            throw DemoException(json.optString("message").ifEmpty { "no quotes returned" })
        }
        val q = quotes.getJSONObject(0)
        DemoQuote(
            destinationAmount = q.optDoubleOrNull("destinationAmount"),
            totalFee = q.optDoubleOrNull("totalFee"),
            exchangeRate = q.optDoubleOrNull("exchangeRate"),
        )
    }

    /** `POST /crypto/order/headless` — returns the raw order JSON to hand to `MeldOrder.fromJson`. */
    suspend fun createOrder(customerId: String, wallet: String, clientIP: String?): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject(
                buildMap<String, Any?> {
                    put("customerId", customerId)
                    put("externalOrderId", "android-demo-${System.currentTimeMillis()}")
                    put("sessionType", "BUY")
                    put("serviceProvider", "MERCURYO")
                    put("paymentMethodType", "CREDIT_DEBIT_CARD")
                    put("sourceCurrencyCode", DemoConfig.SOURCE_CURRENCY)
                    put("sourceAmount", DemoConfig.SOURCE_AMOUNT)
                    put("destinationCurrencyCode", DemoConfig.DESTINATION_CURRENCY)
                    put("destinationWalletAddress", wallet)
                    put("countryCode", DemoConfig.COUNTRY)
                    if (clientIP != null) put("clientIpAddress", clientIP)
                },
            )
            val (status, data) = post("/crypto/order/headless", body)
            if (status !in 200..299) { // headless order returns 201 Created
                val info = runCatching { JSONObject(data) }.getOrNull()
                val code = info?.optString("code")?.ifEmpty { null } ?: status.toString()
                val message = info?.optString("message")?.ifEmpty { null } ?: "order creation failed"
                throw DemoException("$code — $message")
            }
            data
        }

    private fun post(path: String, body: JSONObject): Pair<Int, String> {
        val conn = (URL(DemoConfig.apiBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "BASIC ${DemoConfig.meldApiKey}")
            setRequestProperty("Meld-Version", DemoConfig.VERSION)
            setRequestProperty("X-Idempotency-Key", UUID.randomUUID().toString())
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            status to text
        } finally {
            conn.disconnect()
        }
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
