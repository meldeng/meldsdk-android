package io.meld.sdk

/** Advisory quote/order protocol. Unknown values remain readable but are not executable. */
data class MeldHeadlessPresentation(val surface: String, val protocol: String, val version: Int)

internal sealed class PresentationDeclaration {
    object Absent : PresentationDeclaration()
    object Invalid : PresentationDeclaration()
    data class Declared(val value: MeldHeadlessPresentation) : PresentationDeclaration()

    companion object {
        fun decode(order: Map<String, Any?>): PresentationDeclaration {
            if (!order.containsKey("headlessPresentation")) return Absent
            val value = order["headlessPresentation"] as? Map<*, *> ?: return Invalid
            val surface = value["surface"] as? String ?: return Invalid
            val protocol = value["protocol"] as? String ?: return Invalid
            val number = value["version"] as? Number ?: return Invalid
            // RN maps JSON numbers to doubles; accept exact integral values without truncation.
            val version = try {
                number.toString().toBigDecimal().intValueExact()
            } catch (_: IllegalArgumentException) {
                return Invalid
            } catch (_: ArithmeticException) {
                return Invalid
            }
            if (surface.isBlank() || protocol.isBlank() || version <= 0) return Invalid
            return Declared(MeldHeadlessPresentation(surface, protocol, version))
        }
    }
}

internal data class AdapterPresentation(
    val paymentMethodType: String,
    val presentation: MeldHeadlessPresentation,
)

/** Immutable registry: new adapters declare support without changing shared dispatch. */
internal class MeldAdapterRegistry(adapters: List<MeldAdapter>) {
    private val legacy = adapters.toList()
    private val declared = buildMap<AdapterPresentation, MeldAdapter> {
        for (adapter in legacy) for (key in adapter.presentations) {
            require(put(key, adapter) == null) { "Duplicate presentation adapter registration" }
        }
    }

    fun adapter(presentation: MeldHeadlessPresentation, paymentMethodType: String): MeldAdapter? =
        declared[AdapterPresentation(paymentMethodType, presentation)]

    fun adapter(order: MeldOrder): MeldAdapter? = when (val declaration = order.presentationDeclaration) {
        PresentationDeclaration.Absent -> legacy.firstOrNull { it.matches(order) }
        PresentationDeclaration.Invalid -> null
        is PresentationDeclaration.Declared -> order.paymentMethodType?.let { adapter(declaration.value, it) }
    }
}
