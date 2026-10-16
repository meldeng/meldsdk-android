package io.meld.sdk

/** Normalized [MeldError.code] values. The provider's own event and code travel in [MeldError.detail]. */
enum class MeldErrorCode(val raw: String, val attemptMayExist: Boolean) {
    /** No attempt exists. This device or customer cannot pay with the order's method; offer another. */
    PAYMENT_METHOD_UNAVAILABLE("PAYMENT_METHOD_UNAVAILABLE", false),

    /** No attempt exists. The next attempt needs a new order. */
    PRESENTATION_FAILED("PRESENTATION_FAILED", false),

    /** Declined; nothing will settle. Offer another option. */
    PAYMENT_REJECTED("PAYMENT_REJECTED", false),

    /** No attempt exists. The provider no longer accepts this order. */
    ORDER_STATE_CHANGED("ORDER_STATE_CHANGED", false),

    /** An attempt may exist. Track the order and never pay it again. */
    PAYMENT_OUTCOME_UNKNOWN("PAYMENT_OUTCOME_UNKNOWN", true),
    ;

    companion object {
        /**
         * False only for the codes above that say no attempt exists. Every other code, including one
         * this build does not know, may follow a payment: track the order and never pay it again.
         */
        @JvmStatic
        fun attemptMayExist(code: String): Boolean = entries.firstOrNull { it.raw == code }?.attemptMayExist ?: true
    }
}

/** [MeldError.message] copy for outcomes that read the same whatever the provider. */
internal object MeldErrorMessage {
    const val PAYMENT_REJECTED = "This payment was declined. Some banks block crypto purchases on credit cards, " +
        "so try a debit card or another payment method."
}
