package io.meld.sdk

import io.meld.sdk.actions.apiBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeldEnvironmentTest {
    @Test fun qa_matches_ios_raw_values_base_urls_and_entry_order() {
        assertEquals(listOf("sandbox", "qa", "production"), MeldEnvironment.entries.map { it.raw })
        assertEquals("https://api-sb.meld.io", MeldEnvironment.SANDBOX.apiBaseUrl)
        assertEquals("https://api-qa.meld.io", MeldEnvironment.QA.apiBaseUrl)
        assertEquals("https://api.meld.io", MeldEnvironment.PRODUCTION.apiBaseUrl)
    }

    @Test fun qa_is_checked_as_sandbox() {
        assertEquals(MeldEnvironment.SANDBOX, MeldEnvironment.SANDBOX.providerEnvironment)
        assertEquals(MeldEnvironment.SANDBOX, MeldEnvironment.QA.providerEnvironment)
        assertEquals(MeldEnvironment.PRODUCTION, MeldEnvironment.PRODUCTION.providerEnvironment)
    }

    @Test fun a_qa_configuration_with_a_sandbox_provider_host_is_no_mismatch() {
        for (host in listOf("sandbox-widget.mrcr.io", "sandbox-exchange.mrcr.io")) {
            assertNull(MercuryoCardAdapter.environmentMismatch(host, MeldEnvironment.QA))
            assertNull(MercuryoCardAdapter.environmentMismatch(host, MeldEnvironment.SANDBOX))
            assertEquals(MeldEnvironment.SANDBOX, MercuryoCardAdapter.environmentMismatch(host, MeldEnvironment.PRODUCTION))
        }
        assertEquals(MeldEnvironment.PRODUCTION, MercuryoCardAdapter.environmentMismatch("widget.mercuryo.io", MeldEnvironment.QA))
        assertNull(MercuryoCardAdapter.environmentMismatch("widget.mercuryo.io", MeldEnvironment.PRODUCTION))
        assertNull(MercuryoCardAdapter.environmentMismatch("unknown.example", MeldEnvironment.QA))
        assertNull(MercuryoCardAdapter.environmentMismatch(null, MeldEnvironment.QA))

        for (host in listOf("api.enterprise.sandbox.uphold.com", "payment-widget.enterprise.sandbox.uphold.com")) {
            assertNull(UpholdCardAdapter.environmentMismatch(host, MeldEnvironment.QA))
            assertEquals(MeldEnvironment.SANDBOX, UpholdCardAdapter.environmentMismatch(host, MeldEnvironment.PRODUCTION))
        }
        assertEquals(MeldEnvironment.PRODUCTION, UpholdCardAdapter.environmentMismatch("api.enterprise.uphold.com", MeldEnvironment.QA))
        assertNull(UpholdCardAdapter.environmentMismatch(null, MeldEnvironment.QA))
    }
}
