package io.meld.sdk

import java.net.URI

/** Pure JVM parsing also keeps legacy selection testable without Android URI stubs. */
internal fun isRegisteredWidgetUrl(value: String?, origins: Set<String>): Boolean {
    if (value.isNullOrEmpty()) return false
    return try {
        val uri = URI(value)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.host != null &&
            (uri.port == -1 || uri.port == 443) && "https://${uri.host}" in origins
    } catch (_: Exception) {
        false
    }
}
