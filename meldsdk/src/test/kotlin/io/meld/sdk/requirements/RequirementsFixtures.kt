package io.meld.sdk.requirements

/** The shared fixtures in `requirements-fixtures/`, read from the test classpath. */
internal object RequirementsFixtures {
    const val ROOT = "requirements-fixtures"

    fun bytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$ROOT/$path")) { "missing fixture $path" }.use { it.readBytes() }
}
