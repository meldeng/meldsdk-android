package io.meld.sdk.requirements

import io.meld.sdk.actions.strictInt
import org.json.JSONObject
import java.security.MessageDigest

/** A decoded READ. Members the SDK does not know stay in [raw]; a missing required one fails the parse. */
internal class RequirementsSnapshot private constructor(
    val raw: Map<String, Any?>,
    val status: String,
    val pollAfterMs: Long,
    val providers: List<Provider>,
    val next: List<MeldReadyStep>,
    val waitingOn: List<*>,
    /** Equal for two reads that differ only in clocks and freshly minted links. */
    val fingerprint: String,
) {
    class Provider(val serviceProvider: String, val status: String, val eligible: Boolean?, val requirements: List<Requirement>)

    class Requirement(
        val type: String,
        val code: String,
        val status: String,
        val timing: String?,
        val reason: String?,
        val errorCodes: List<String>?,
        val nextStep: String?,
    )

    fun results(): List<MeldReadyProvider> = providers.map { provider ->
        val open = provider.requirements.filter { it.timing != ORDER_CONTINUATION && it.status != SATISFIED }
        MeldReadyProvider(provider.serviceProvider, provider.status, provider.eligible,
            open.map { MeldReadyOpenStep(it.type, it.code, it.status, it.reason, it.errorCodes) })
    }

    fun continuation(): List<MeldReadyContinuation> = providers.flatMap { provider ->
        provider.requirements.filter { it.timing == ORDER_CONTINUATION && it.status != SATISFIED }
            .map { MeldReadyContinuation(provider.serviceProvider, it.code, it.nextStep) }
    }

    companion object {
        private const val SATISFIED = "SATISFIED"
        private const val ORDER_CONTINUATION = "ORDER_CONTINUATION"
        private const val DEFAULT_POLL_MS = 4_000L
        private val POLL_RANGE_MS = 1_000.0..60_000.0
        private val VOLATILE = setOf("expiresAt", "url")

        fun parse(raw: Map<String, Any?>): RequirementsSnapshot? {
            if (strictInt(raw["version"]) != 1) return null
            val status = raw["status"] as? String ?: return null
            val providers = (raw["providers"] as? List<*>)?.map { provider(it) ?: return null } ?: return null
            val next = (raw["next"] as? List<*>)?.map { step(it) ?: return null } ?: return null
            val waitingOn = raw["waitingOn"] as? List<*> ?: return null
            val poll = (raw["pollAfterMs"] as? Number)?.toDouble()?.coerceIn(POLL_RANGE_MS)?.toLong() ?: DEFAULT_POLL_MS
            return RequirementsSnapshot(raw, status, poll, providers, next, waitingOn, fingerprint(raw))
        }

        private fun provider(raw: Any?): Provider? {
            val provider = raw as? Map<*, *> ?: return null
            val serviceProvider = provider["serviceProvider"] as? String ?: return null
            val status = provider["status"] as? String ?: return null
            val requirements = (provider["requirements"] as? List<*>).orEmpty().mapNotNull(::requirement)
            return Provider(serviceProvider, status, provider["eligible"] as? Boolean, requirements)
        }

        private fun requirement(raw: Any?): Requirement? {
            val requirement = raw as? Map<*, *> ?: return null
            return Requirement(
                type = requirement["type"] as? String ?: return null,
                code = requirement["code"] as? String ?: return null,
                status = requirement["status"] as? String ?: return null,
                timing = requirement["timing"] as? String,
                reason = requirement["reason"] as? String,
                errorCodes = errorCodes(requirement["errorCodes"]),
                nextStep = (requirement["action"] as? Map<*, *>)?.get("nextStep") as? String,
            )
        }

        /** A non-empty list of strings, else null. */
        private fun errorCodes(raw: Any?): List<String>? =
            (raw as? List<*>)?.map { it as? String ?: return null }?.takeIf { it.isNotEmpty() }

        private fun step(raw: Any?): MeldReadyStep? {
            val step = raw as? Map<*, *> ?: return null
            val stepId = (step["stepId"] as? String)?.takeIf { it.length in 1..256 } ?: return null
            val providers = (step["serviceProviders"] as? List<*>)?.map { it as? String ?: return null }
            if (providers.isNullOrEmpty()) return null
            return MeldReadyStep(
                stepId = stepId,
                kind = step["kind"] as? String ?: return null,
                type = step["type"] as? String ?: return null,
                code = step["code"] as? String ?: return null,
                serviceProviders = providers,
                level = step["level"] as? String,
                url = step["url"] as? String,
                expiresAt = step["expiresAt"] as? String,
                fields = fields(step["fields"]),
                reason = step["reason"] as? String,
            )
        }

        fun fields(raw: Any?): List<MeldCollectField> = (raw as? List<*>).orEmpty().mapNotNull { entry ->
            val field = entry as? Map<*, *> ?: return@mapNotNull null
            MeldCollectField(
                key = (field["key"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                label = field["label"] as? String,
                fieldType = field["fieldType"] as? String,
                jsonSchema = field["jsonSchema"] as? String,
                allowedValues = (field["allowedValues"] as? List<*>)?.filterIsInstance<String>(),
            )
        }

        /** Ignores every `expiresAt` and `url`, at any depth, and the top-level `pollAfterMs`. */
        private fun fingerprint(raw: Map<String, Any?>): String {
            val canonical = StringBuilder().also { canonical(raw - "pollAfterMs", it) }.toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun canonical(value: Any?, out: StringBuilder) {
            when (value) {
                is Map<*, *> -> {
                    out.append('{')
                    for ((key, entry) in value.entries.filter { it.key !in VOLATILE }.sortedBy { it.key.toString() }) {
                        out.append(JSONObject.quote(key.toString())).append(':')
                        canonical(entry, out)
                        out.append(',')
                    }
                    out.append('}')
                }
                is List<*> -> {
                    out.append('[')
                    for (entry in value) {
                        canonical(entry, out)
                        out.append(',')
                    }
                    out.append(']')
                }
                is String -> out.append(JSONObject.quote(value))
                else -> out.append(value)
            }
        }
    }
}

/** A decoded RUN_STEP answer. */
internal class RequirementsOutcome private constructor(
    val outcome: String,
    val url: String?,
    val expiresAt: String?,
    val fields: List<MeldCollectField>,
    val continuation: String?,
) {
    companion object {
        const val DONE = "DONE"
        const val OPEN_URL = "OPEN_URL"
        const val COLLECT = "COLLECT"

        /** The answer for [stepId], or null when it is for another step or has no outcome. */
        fun parse(raw: Map<String, Any?>, stepId: String): RequirementsOutcome? {
            if (raw["stepId"] != stepId) return null
            return RequirementsOutcome(
                outcome = raw["outcome"] as? String ?: return null,
                url = raw["url"] as? String,
                expiresAt = raw["expiresAt"] as? String,
                fields = RequirementsSnapshot.fields(raw["fields"]),
                continuation = (raw["continuation"] as? String)?.takeIf { it.length in 1..64 },
            )
        }
    }
}
