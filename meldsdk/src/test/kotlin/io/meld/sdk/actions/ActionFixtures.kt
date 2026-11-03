package io.meld.sdk.actions

import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldOrder

/** Only synthetic order and action data. No network is needed by these fixtures. */
internal object ActionFixtures {
    const val ENDPOINT = "/crypto/order/headless/onramp/TEST_PROVIDER/test-order/actions"
    const val URL = "https://api-sb.meld.io$ENDPOINT"

    fun actions(): Map<String, Any?> = mapOf(
        "version" to 1,
        "endpoint" to ENDPOINT,
        "bearerTokenPointer" to "/paymentMethodResponseDetails/sessionToken",
        "operations" to listOf(
            mapOf("operation" to "READ_SUBMISSION", "idempotencyKeyRequired" to false),
            mapOf("operation" to "SUBMIT_WALLET_PAYMENT", "idempotencyKeyRequired" to true),
        ),
    )

    fun json(): Map<String, Any?> = mapOf(
        "id" to "test-order",
        "paymentMethodType" to "GOOGLE_PAY",
        "payload" to mapOf("serviceProvider" to "TEST_PROVIDER"),
        "headlessPresentation" to mapOf("version" to 1, "surface" to "NATIVE_TOKEN", "protocol" to "MELD_WALLET_TOKEN"),
        "paymentMethodResponseDetails" to mapOf("sessionToken" to "synthetic-session"),
        "paymentActions" to actions(),
    )

    fun order(json: Map<String, Any?> = json()): MeldOrder = MeldOrder.fromMap(json)

    fun descriptor(
        action: Map<String, Any?> = emptyMap(),
        environment: MeldEnvironment = MeldEnvironment.SANDBOX,
    ): PaymentActionDescriptor = PaymentActionDescriptor.parse(order(json() + ("paymentActions" to actions() + action)), environment)
}
