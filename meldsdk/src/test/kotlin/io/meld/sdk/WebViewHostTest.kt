package io.meld.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the hosted-page options; the WebView itself needs a device. */
class WebViewHostTest {

    @Test
    fun `only an https url on an exact pinned host is pinned`() {
        val hosts = setOf("pay.example.com")
        for ((url, pinned) in listOf(
            "https://pay.example.com/buy?x=1" to true,
            "https://pay.example.com:443/buy" to true,
            "http://pay.example.com/buy" to false,
            "https://evil.pay.example.com/" to false,
            "https://example.com/" to false,
            "https://pay.example.com.evil.test/" to false,
            "https://user:secret@pay.example.com/" to false,
            "https://pay.example.com:8443/" to false,
            "about:blank" to false,
            "not a url" to false,
        )) {
            assertEquals(url, pinned, isPinnedMainFrameUrl(url, hosts))
        }
        assertFalse(isPinnedMainFrameUrl(null, hosts))
    }

    @Test
    fun `nothing is pinned without hosts`() {
        assertFalse(isPinnedMainFrameUrl("https://pay.example.com/", emptySet()))
    }

    @Test
    fun `a script's string result is decoded and anything else is null`() {
        assertEquals("clicked", scriptStringResult("\"clicked\""))
        assertEquals("a \"quoted\" value", scriptStringResult("\"a \\\"quoted\\\" value\""))
        assertTrue(scriptStringResult("\"\"")!!.isEmpty())
        for (other in listOf(null, "null", "42", "true", "{}", "[]", "", "not json")) {
            assertNull(other, scriptStringResult(other))
        }
    }
}
