package io.meld.sdk.requirements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The shared requirements fixtures are byte-identical to payment-domain's copy. */
class RequirementsFixturesManifestTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun the_manifest_is_the_contract_s_and_every_file_matches_it() {
        val manifest = RequirementsFixtures.bytes("SHA256SUMS")
        assertEquals(MANIFEST_DIGEST, sha256(manifest))

        val listed = String(manifest, Charsets.UTF_8).lines().filter { it.isNotEmpty() }.map { line ->
            val (digest, path) = line.split("  ", limit = 2)
            assertEquals(path, digest, sha256(RequirementsFixtures.bytes(path)))
            path
        }
        assertEquals(listed.sorted(), listed)
        assertTrue(listed.isNotEmpty())

        val root = File(checkNotNull(javaClass.getResource("/${RequirementsFixtures.ROOT}")).toURI())
        val present = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        assertEquals(listed.toSet() + "SHA256SUMS", present)
    }

    private companion object {
        const val MANIFEST_DIGEST = "32de87754495673aa11c334360d124bfcc7c68b77910fe81750c37d2e6e38383"
    }
}
