package io.meld.sdk.coinme

import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldOrder

/** The order shape `POST /crypto/order/headless/onramp` returns for a Coinme card. */
internal object CoinmeFixtures {
    const val ORDER_ID = "2Hqf9kceamXGQZJQxMQh89"
    const val BROWSER_TOKEN = "coinme.browser.token"

    val VISA = card("pm_visa", "Visa", "1111")

    fun card(id: String, brand: String?, last4: String) = mapOf(
        "paymentMethodId" to id, "brand" to brand, "bin" to "411111", "last4" to last4,
        "expMonth" to "12", "expYear" to "2030",
    )

    fun op(name: String, required: Boolean) = mapOf("operation" to name, "idempotencyKeyRequired" to required)

    val OPERATIONS = listOf(op("CREATE_CUSTOMER_AUTH_TOKEN", true), op("CREATE_PAYMENT_SESSION", true))

    fun orderMap(
        savedCards: Any? = listOf(VISA),
        environment: String = "sandbox",
        operations: List<Map<String, Any?>> = OPERATIONS,
        presentation: Map<String, Any?> = mapOf("surface" to "VENDOR_SDK", "protocol" to "COINME_VAULT", "version" to 1),
        paymentMethodType: String = "CREDIT_DEBIT_CARD",
        sessionData: (MutableMap<String, Any?>) -> Unit = {},
        details: (MutableMap<String, Any?>) -> Unit = {},
    ): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>(
            "customerId" to "1428417779958550528",
            "paymentProviderId" to "828627387817136137",
            "environment" to environment,
            "riskClientId" to "risk-client",
            "riskPartnerId" to "sIxOAhP5cXLmxR8ZjCLN",
            "savedCards" to savedCards,
        ).also(sessionData)
        val responseDetails = mutableMapOf<String, Any?>(
            "renderMode" to "IFRAME",
            "continuationToken" to "continuation.jwt",
            "sdkSessionToken" to BROWSER_TOKEN,
            "sdkSessionData" to data,
        ).also(details)
        return mapOf(
            "id" to ORDER_ID,
            "paymentMethodType" to paymentMethodType,
            "responseKind" to "SESSION",
            "headlessPresentation" to presentation,
            "paymentMethodResponseDetails" to responseDetails,
            "payload" to mapOf("serviceProvider" to "COINME", "sourceAmount" to 20, "sourceCurrencyCode" to "USD"),
            "paymentActions" to mapOf(
                "version" to 1,
                "endpoint" to "/crypto/order/headless/onramp/COINME/$ORDER_ID/actions",
                "bearerTokenPointer" to "/paymentMethodResponseDetails/continuationToken",
                "operations" to operations,
            ),
        )
    }

    fun order(savedCards: Any? = listOf(VISA)) = MeldOrder.fromMap(orderMap(savedCards))

    fun coinme(savedCards: Any? = listOf(VISA)) = CoinmeOrder.parse(order(savedCards), MeldEnvironment.SANDBOX)!!
}
