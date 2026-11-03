package io.meld.sdk.wallet

import io.meld.sdk.actions.MAX_ACTION_BODY_BYTES
import io.meld.sdk.actions.PaymentActionException
import io.meld.sdk.actions.encodeActionObject

/** The billing address a wallet returned. Redacted. */
internal class WalletBillingAddress(
    val countryCode: String? = null,
    val streetLine1: String? = null,
    val streetLine2: String? = null,
    val stateCode: String? = null,
    val city: String? = null,
    val zipCode: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(countryCode, streetLine1, streetLine2, stateCode, city, zipCode).all { it.isNullOrEmpty() }

    override fun toString(): String = "WalletBillingAddress[REDACTED]"
}

/**
 * What a wallet sheet hands the session. The sheet checks its own token's format (Apple's is base64,
 * Google's gateway token is JSON); [actionFields] checks the limits the server enforces. Redacted.
 */
internal class WalletPayment(
    val token: String,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val billing: WalletBillingAddress,
) {
    override fun toString(): String = "WalletPayment[REDACTED]"

    /** The SUBMIT_WALLET_PAYMENT fields, or [PaymentActionException.InvalidRequest] before anything is claimed or sent. */
    fun actionFields(): Map<String, Any?> {
        if (token.isEmpty() || token.toByteArray(Charsets.UTF_8).size > MAX_TOKEN_BYTES) invalid()
        if (email == null || !valid(email, 254) || !EMAIL.matches(email)) invalid()
        if (!valid(firstName, 255) || !valid(lastName, 255) || billing.isEmpty) invalid()
        val address = LinkedHashMap<String, String>()
        for ((name, value, maximum) in listOf(
            Triple("countryCode", billing.countryCode, 2), Triple("streetLine1", billing.streetLine1, 255),
            Triple("streetLine2", billing.streetLine2, 255), Triple("stateCode", billing.stateCode, 128),
            Triple("city", billing.city, 255), Triple("zipCode", billing.zipCode, 32),
        )) {
            if (value.isNullOrEmpty()) continue
            if (!valid(value, maximum)) invalid()
            address[name] = value
        }
        address["countryCode"]?.let { if (!COUNTRY.matches(it)) invalid() }
        val fields = mapOf(
            "walletPayment" to mapOf(
                "token" to token, "email" to email, "firstName" to firstName, "lastName" to lastName, "billingAddress" to address,
            ),
        )
        val body = encodeActionObject(fields + mapOf("version" to 1, "operation" to "SUBMIT_WALLET_PAYMENT"))
        if (body.size > MAX_ACTION_BODY_BYTES) invalid()
        return fields
    }

    private companion object {
        const val MAX_TOKEN_BYTES = 49_152
        val EMAIL = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")
        val COUNTRY = Regex("[A-Z]{2}")

        /** Not blank, at most [maximum] UTF-16 units, and no control or format characters. */
        fun valid(value: String, maximum: Int): Boolean {
            if (value.isBlank() || value.length > maximum) return false
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val type = Character.getType(codePoint)
                if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) return false
                index += Character.charCount(codePoint)
            }
            return true
        }

        fun invalid(): Nothing = throw PaymentActionException.InvalidRequest
    }
}
