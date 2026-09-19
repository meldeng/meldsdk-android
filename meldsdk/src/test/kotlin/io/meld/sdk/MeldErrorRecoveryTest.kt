package io.meld.sdk

import org.junit.Assert.*
import org.junit.Test

class MeldErrorRecoveryTest {
    @Test fun legacyConstructorAndCopyRetainSharedRecoveryRegardlessOfRecoverability() {
        for (recoverable in listOf(false, true)) {
            val error = MeldError("synthetic-order", "synthetic-code", "synthetic", null, recoverable)
            val (order, code, message, detail, legacyRecoverable) = error
            assertEquals("synthetic-order", order)
            assertEquals("synthetic-code", code)
            assertEquals("synthetic", message)
            assertNull(detail)
            assertEquals(recoverable, legacyRecoverable)
            for (value in listOf(error, error.copy(recoverable = !recoverable))) {
                assertEquals(1, value.headlessError.version)
                assertEquals("OUTCOME_UNKNOWN", value.headlessError.category)
                assertEquals("READ_STATE", value.headlessError.recovery)
                assertFalse(value.headlessError.automaticRetryAllowed)
            }
        }
    }
}
