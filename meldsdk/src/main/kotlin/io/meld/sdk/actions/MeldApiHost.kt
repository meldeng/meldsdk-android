package io.meld.sdk.actions

import io.meld.sdk.MeldEnvironment

/** The Meld API origin of each backend, the same as iOS `PaymentActionDescriptor.baseURL`. */
internal val MeldEnvironment.apiBaseUrl: String
    get() = when (this) {
        MeldEnvironment.SANDBOX -> "https://api-sb.meld.io"
        MeldEnvironment.QA -> "https://api-qa.meld.io"
        MeldEnvironment.PRODUCTION -> "https://api.meld.io"
    }
