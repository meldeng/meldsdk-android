package io.meld.sdk.actions

import io.meld.sdk.MeldOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class ActionJsonTest {
    @Test fun integers_are_exact_whatever_number_class_decoded_them() {
        for (one in listOf<Any>(1, 1L, 1.0, 1.0f, BigDecimal("1.00"), BigInteger.ONE, 1.toShort())) {
            assertEquals(one.javaClass.simpleName, 1, strictInt(one))
        }
        for (bad in listOf<Any?>(null, true, "1", 1.5, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE, emptyMap<String, Any>())) {
            assertNull(bad.toString(), strictInt(bad))
        }
    }

    @Test fun booleans_are_only_json_booleans() {
        assertEquals(true, strictBoolean(true))
        assertEquals(false, strictBoolean(false))
        for (bad in listOf<Any?>(null, 0, 1, "true", "false")) assertNull(bad.toString(), strictBoolean(bad))
    }

    @Test fun pointer_escapes_and_array_indices_resolve_without_guessing() {
        val root = mapOf("a/b" to mapOf("~key" to listOf("synthetic-escaped", null)), "" to mapOf("" to "empty"))
        assertEquals("synthetic-escaped", jsonPointer(root, "/a~1b/~0key/0"))
        assertEquals("empty", jsonPointer(root, "//"))
        for (pointer in listOf("", "a~1b", "/a~1b/~0key/00", "/a~1b/~0key/-0", "/a~1b/~0key/+0", "/a~1b/~0key/1",
            "/a~1b/~0key/2", "/a~1b/~0key/-1", "/a~1b/..", "/a~2b", "/a~", "/a/b", "/missing", "/a~1b/~0key/0/deeper")) {
            assertNull(pointer, jsonPointer(root, pointer))
        }
    }

    @Test fun pointers_are_bounded_at_1024_bytes() {
        val key = "k".repeat(1023)
        assertEquals("v", jsonPointer(mapOf(key to "v"), "/$key"))
        assertNull(jsonPointer(mapOf("${key}k" to "v"), "/${key}k"))
        assertNull(jsonPointer(mapOf("é".repeat(512) to "v"), "/" + "é".repeat(512)))
    }

    @Test fun the_order_keeps_its_whole_response_for_server_declared_pointers() {
        val order = MeldOrder.fromJson("""{"id":"o1","paymentMethodResponseDetails":{"sessionToken":"synthetic"},
            "paymentActions":{"version":1},"extra":[{"x":null}]}""")
        assertEquals("synthetic", jsonPointer(order.raw, "/paymentMethodResponseDetails/sessionToken"))
        assertEquals(1, strictInt(jsonPointer(order.raw, "/paymentActions/version")))
        assertEquals(true, order.raw.containsKey("extra"))
        assertNull(jsonPointer(order.raw, "/extra/0/x"))
        assertEquals(mapOf("id" to "o2"), MeldOrder.fromMap(mapOf("id" to "o2")).raw)
    }
}
