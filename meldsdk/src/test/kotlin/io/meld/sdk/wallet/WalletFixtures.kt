package io.meld.sdk.wallet

/** Only synthetic wallet and action data. */
internal object WalletFixtures {
    /** 2030-03-17T17:46:40Z. */
    const val NOW = 1_900_000_000_000L

    val payment = WalletPayment(
        token = """{"signature":"synthetic","protocolVersion":"ECv2","signedMessage":"{\"encryptedMessage\":\"synthetic\"}"}""",
        firstName = "Test",
        lastName = "Customer",
        email = "test@example.com",
        billing = WalletBillingAddress(countryCode = "LT", streetLine1 = "1 Test Street", city = "Test", zipCode = "12345"),
    )

    fun response(
        state: String,
        disposition: String = "RESUME_PAYMENT",
        expires: String = "2030-03-17T17:51:40Z",
    ): Map<String, Any?> {
        val next = when (state) {
            "NOT_STARTED", "FAILED" -> "NONE"
            "SUBMITTED", "EXPIRED" -> "WAIT_FOR_PAYMENT"
            "VERIFICATION_REQUIRED" -> "OPEN_HOSTED_VERIFICATION"
            else -> "WAIT_FOR_PROVIDER"
        }
        val json = mutableMapOf<String, Any?>("version" to 1, "status" to state, "nextStep" to next)
        if (state == "VERIFICATION_REQUIRED") {
            json["verification"] = mapOf(
                "url" to "https://sandbox-exchange.mrcr.io/verification?session=synthetic",
                "paymentDisposition" to disposition,
                "expiresAt" to expires,
            )
        }
        return json
    }
}
