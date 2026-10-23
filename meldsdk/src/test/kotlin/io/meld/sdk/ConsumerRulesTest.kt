package io.meld.sdk

import com.google.android.gms.wallet.Wallet
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A minified app that excludes optional Play services libraries must still pass R8's missing-class check. */
class ConsumerRulesTest {
    private val dontWarn = File("consumer-rules.pro").readLines()
        .mapNotNull { Regex("""^-dontwarn\s+(\S+)$""").find(it.trim())?.groupValues?.get(1) }
        .toSet()

    @Test
    fun `only classes the consumer rules exempt reference Play services`() {
        val referencing = references(Meld::class.java, "com/google/android/gms/").keys

        assertEquals(setOf("io.meld.sdk.googlepay.PlayServicesGooglePay"), referencing)
        assertTrue("$referencing not all in $dontWarn", dontWarn.containsAll(referencing))
    }

    @Test
    fun `every Maps class Wallet references is exempt, since the SDK excludes Maps`() {
        val maps = references(Wallet::class.java, "com/google/android/gms/maps/").values.flatten().toSet()

        assertEquals(setOf("com.google.android.gms.maps.model.LatLng"), maps)
        assertTrue("$maps not all in $dontWarn", dontWarn.containsAll(maps))
    }

    /** The classes in [of]'s class path entry that name a type under [prefix], with the types they name. */
    private fun references(of: Class<*>, prefix: String): Map<String, Set<String>> {
        val location = File(of.protectionDomain.codeSource.location.toURI())
        val name = Regex(Regex.escape(prefix) + "[A-Za-z0-9_/$]+")
        val classes: Map<String, ByteArray> = if (location.isDirectory) {
            location.walk().filter { it.extension == "class" }
                .associate { it.relativeTo(location).invariantSeparatorsPath to it.readBytes() }
        } else {
            ZipFile(location).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") }
                    .associate { it.name to jar.getInputStream(it).use { input -> input.readBytes() } }
            }
        }
        return classes
            .map { (path, bytes) ->
                path.removeSuffix(".class").replace('/', '.') to
                    name.findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value.replace('/', '.') }.toSet()
            }
            .filter { it.second.isNotEmpty() }
            .toMap()
    }
}
