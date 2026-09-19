package io.meld.sdk

/**
 * Provider-neutral recovery advice for Android presentation callbacks.
 * These callbacks cannot prove payment outcome or read-only operation semantics.
 * Preserve the existing order; never infer permission for a replacement payment.
 */
class MeldHeadlessError internal constructor() {
    val version: Int = 1
    val category: String = "OUTCOME_UNKNOWN"
    val recovery: String = "READ_STATE"
    val automaticRetryAllowed: Boolean = false
}
