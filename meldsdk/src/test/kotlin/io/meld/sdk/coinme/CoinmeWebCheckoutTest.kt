package io.meld.sdk.coinme

import io.meld.sdk.MeldError
import io.meld.sdk.MeldEvent
import io.meld.sdk.actions.PaymentActionResponse
import io.meld.sdk.coinme.CoinmeWebCheckout.Companion.actionRequest
import io.meld.sdk.coinme.CoinmeWebCheckout.Companion.bootstrapHtml
import io.meld.sdk.coinme.CoinmeWebCheckout.Companion.events
import io.meld.sdk.coinme.CoinmeWebCheckout.Companion.resultScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class CoinmeWebCheckoutTest {
    private val key = "123e4567-e89b-42d3-a456-426614174000"

    private fun action(body: Any?, key: Any? = this.key) =
        mapOf("type" to "action", "id" to "1", "body" to body, "headers" to mapOf("X-Idempotency-Key" to key))

    @Test
    fun `the page mounts the order with the web SDK and sends its actions to native`() {
        val html = bootstrapHtml("/*bundle*/", """{"id":"ord_1"}""", "qa")

        assertTrue(html.contains("<script>/*bundle*/</script>"))
        assertTrue(html.contains("""M.configure({environment:["qa"][0]});"""))
        assertTrue(html.contains("""M.mount({"id":"ord_1"}, document.getElementById('meld-coinme')"""))
        assertTrue(html.contains("M.__setActionTransport("))
        assertTrue(html.contains("post({type:'action', id:id, headers:request.headers, body:request.body});"))
    }

    @Test
    fun `a closing script tag in the bundle or the order cannot end its script early`() {
        val html = bootstrapHtml(
            "var a='</script><b>';",
            """{"note":"</script><img src=x>","c":"<!--<script>"}""",
            "sandbox",
        )

        assertFalse(html.contains("</script><b>"))
        assertFalse(html.contains("</script><img"))
        assertFalse(html.contains("<!--"))
        assertTrue(html.contains("""{"note":"\u003c/script>\u003cimg src=x>","c":"\u003c!--\u003cscript>"}"""))
    }

    @Test
    fun `the web SDK's callbacks become native events`() {
        assertEquals(listOf(MeldEvent.Ready), events(mapOf("type" to "ready"), "ord_1"))
        assertEquals(listOf(MeldEvent.PaymentSubmitted), events(mapOf("type" to "submitted"), "ord_1"))
        assertEquals(listOf(MeldEvent.Cancel), events(mapOf("type" to "cancel"), "ord_1"))
        assertEquals(
            listOf(MeldEvent.Error(MeldError("ord_1", "PAYMENT_REJECTED", "Declined", "vault:231-400-313-601", false))),
            events(
                mapOf("type" to "error", "code" to "PAYMENT_REJECTED", "message" to "Declined",
                    "detail" to "vault:231-400-313-601", "recoverable" to false),
                "ord_1",
            ),
        )
        val bare = events(mapOf("type" to "error"), "ord_1").single() as MeldEvent.Error
        assertEquals("PRESENTATION_FAILED", bare.error.code)
        assertTrue(events(mapOf("type" to "error", "code" to "PROVIDER_LOAD_FAILED", "recoverable" to true), null)
            .let { (it.single() as MeldEvent.Error).error.recoverable })
        assertEquals(emptyList<MeldEvent>(), events(mapOf("type" to "status"), "ord_1"))
    }

    @Test
    fun `an action carries its operation, fields and idempotency key`() {
        val request = actionRequest(action("""{"version":1,"operation":"CREATE_PAYMENT_SESSION","paymentToken":"pm_1"}"""))!!

        assertEquals("CREATE_PAYMENT_SESSION", request.operation)
        assertEquals(mapOf("paymentToken" to "pm_1"), request.fields)
        assertEquals(UUID.fromString(key), request.idempotencyKey)
        assertNull(actionRequest(action("""{"version":1,"operation":"READ"}""", key = null))!!.idempotencyKey)
    }

    @Test
    fun `a malformed action is refused`() {
        for (message in listOf(
            action(null),
            action("not json"),
            action("""{"version":1}"""),
            action("""{"version":2,"operation":"CREATE_PAYMENT_SESSION"}"""),
            action("""{"version":1,"operation":"CREATE_PAYMENT_SESSION"}""", key = "not-a-uuid"),
            action("""{"version":1,"operation":"CREATE_PAYMENT_SESSION"}""", key = key.uppercase()),
            action("""{"version":1,"operation":"CREATE_PAYMENT_SESSION"}""", key = 7),
            action("""{"version":1,"operation":"CREATE_PAYMENT_SESSION","pad":"${"x".repeat(65_536)}"}"""),
        )) {
            assertNull(message.toString().take(80), actionRequest(message))
        }
    }

    @Test
    fun `the page gets the raw response, or nothing when there is none`() {
        assertEquals(
            """window.__meldActionResult("7", 422, "{\"version\":1,\"code\":\"PROVIDER_REJECTED\"}");""",
            resultScript("7", PaymentActionResponse(422, """{"version":1,"code":"PROVIDER_REJECTED"}""")),
        )
        assertEquals("""window.__meldActionResult("7", 502, null);""", resultScript("7", PaymentActionResponse(502, null)))
        assertEquals("""window.__meldActionResult("7", null, null);""", resultScript("7", null))
    }

    @Test
    fun `the vendored bundle matches its pinned hash`() {
        val bytes = File("src/main/assets/${CoinmeWebCheckout.BUNDLE_ASSET}").readBytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        assertEquals(CoinmeWebCheckout.EXPECTED_BUNDLE_SHA256, actual)
    }
}
