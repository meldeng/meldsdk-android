package io.meld.sdk.actions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Unit tests stub the framework's AtomicFile, so its write protocol runs here in plain java.io. */
private class JvmAttemptFile(private val file: File) : AttemptFile {
    override fun read(): ByteArray? = readIfPresent(file) { file.readBytes() }

    override fun write(bytes: ByteArray) {
        val next = File(file.path + ".new")
        FileOutputStream(next).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        check(next.renameTo(file)) { "rename failed" }
    }
}

class AttemptStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val identity = "https://api-sb.meld.io/crypto/order/headless/onramp/TEST_PROVIDER/test-order/actions"
    private val directory by lazy { File(folder.root, FileAttemptStore.DIRECTORY) }

    private fun store(id: String = identity, open: (File) -> AttemptFile = ::JvmAttemptFile) = FileAttemptStore({ directory }, id, open)

    private fun recordFile(id: String = identity): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, name)
    }

    private fun fails(expected: PaymentActionException, call: () -> Any) =
        assertSame(expected, assertThrows(PaymentActionException::class.java) { call() })

    @Test fun nothing_is_written_until_the_first_claim() {
        val store = store()
        val first = store.record()
        assertFalse(first.submissionStarted)
        assertFalse(first.verificationOpened)
        assertFalse(directory.exists())
        assertNotEquals(first.submissionKey, store.record().submissionKey)
    }

    @Test fun the_attempt_survives_a_new_store_and_holds_only_the_ios_keys() {
        val key = store().claimSubmission()
        val replay = store()
        assertEquals(AttemptRecord(key, submissionStarted = true, verificationOpened = false), replay.record())
        fails(PaymentActionException.AlreadyAttempted) { replay.claimSubmission() }
        replay.claimVerification()
        val reopened = store()
        assertEquals(AttemptRecord(key, submissionStarted = true, verificationOpened = true), reopened.record())
        fails(PaymentActionException.AlreadyAttempted) { reopened.claimVerification() }

        val json = JSONObject(recordFile().readText())
        assertEquals(setOf("submissionKey", "submissionStarted", "verificationOpened"), json.keySet())
        assertEquals(key.toString(), json.getString("submissionKey"))
        assertEquals(listOf(recordFile().name), directory.list()!!.toList())
    }

    @Test fun claiming_verification_also_fences_the_submission() {
        store().claimVerification()
        val record = store().record()
        assertTrue(record.submissionStarted)
        assertTrue(record.verificationOpened)
        fails(PaymentActionException.AlreadyAttempted) { store().claimSubmission() }
    }

    @Test fun an_observed_server_attempt_fences_a_new_submission_and_keeps_the_key() {
        val store = store()
        store.observeSubmission()
        val key = store.record().submissionKey
        store.observeSubmission()
        assertEquals(AttemptRecord(key, submissionStarted = true), store().record())
        fails(PaymentActionException.AlreadyAttempted) { store().claimSubmission() }
    }

    @Test fun each_identity_has_its_own_record() {
        store().claimSubmission()
        assertFalse(store("$identity/other").record().submissionStarted)
        store("$identity/other").claimSubmission()
        assertEquals(2, directory.list()!!.size)
    }

    @Test fun concurrent_stores_allow_exactly_one_claim() {
        val start = CountDownLatch(1)
        val keys = java.util.Collections.synchronizedList(mutableListOf<UUID>())
        val refusals = java.util.Collections.synchronizedList(mutableListOf<PaymentActionException>())
        val threads = (1..16).map {
            thread {
                start.await()
                try {
                    keys += store().claimSubmission()
                } catch (e: PaymentActionException) {
                    refusals += e
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, keys.size)
        assertEquals(15, refusals.size)
        assertTrue(refusals.all { it === PaymentActionException.AlreadyAttempted })
        assertEquals(keys.single(), store().record().submissionKey)
    }

    @Test fun a_corrupt_record_fails_closed() {
        directory.mkdirs()
        for (contents in listOf("not json", "{}", """{"submissionKey":"not-a-uuid","submissionStarted":false,"verificationOpened":false}""",
            """{"submissionKey":"${UUID.randomUUID()}","submissionStarted":"false","verificationOpened":false}""",
            """{"submissionKey":"${UUID.randomUUID()}","submissionStarted":false}""")) {
            recordFile().writeText(contents)
            fails(PaymentActionException.Storage) { store().record() }
            fails(PaymentActionException.Storage) { store().claimSubmission() }
            fails(PaymentActionException.Storage) { store().claimVerification() }
            fails(PaymentActionException.Storage) { store().observeSubmission() }
            assertEquals(contents, recordFile().readText())
        }
    }

    @Test fun an_unopenable_record_fails_closed_and_only_a_missing_one_reads_as_absent() {
        val unopenable: (File) -> AttemptFile = { file ->
            object : AttemptFile {
                override fun read() = readIfPresent(file) { throw FileNotFoundException("synthetic") }
                override fun write(bytes: ByteArray) = JvmAttemptFile(file).write(bytes)
            }
        }
        assertFalse(store(open = unopenable).record().submissionStarted)
        val key = store().claimSubmission()
        fails(PaymentActionException.Storage) { store(open = unopenable).record() }
        fails(PaymentActionException.Storage) { store(open = unopenable).claimSubmission() }
        fails(PaymentActionException.Storage) { store(open = unopenable).observeSubmission() }
        for (recovery in listOf(File(recordFile().path + ".bak"), File(recordFile().path + ".new"))) {
            assertTrue(recordFile().renameTo(recovery))
            fails(PaymentActionException.Storage) { store().claimSubmission() }
            assertTrue(recovery.renameTo(recordFile()))
        }
        assertEquals(AttemptRecord(key, submissionStarted = true), store().record())
    }

    @Test fun an_unwritable_directory_fails_closed() {
        directory.parentFile!!.mkdirs()
        directory.writeText("a file where the directory belongs")
        assertFalse(store().record().submissionStarted)
        fails(PaymentActionException.Storage) { store().claimSubmission() }
        fails(PaymentActionException.Storage) { store().claimVerification() }
        fails(PaymentActionException.Storage) { store().observeSubmission() }
    }

    @Test fun a_write_that_did_not_land_fails_closed() {
        val silent: (File) -> AttemptFile = { file ->
            object : AttemptFile {
                private val real = JvmAttemptFile(file)
                override fun read() = real.read()
                override fun write(bytes: ByteArray) = Unit
            }
        }
        fails(PaymentActionException.Storage) { store(open = silent).claimSubmission() }
        assertFalse(store().record().submissionStarted)
        val throwing: (File) -> AttemptFile = { object : AttemptFile {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) = throw java.io.IOException("synthetic")
        } }
        fails(PaymentActionException.Storage) { store(open = throwing).claimSubmission() }
        val denied = FileAttemptStore({ throw SecurityException("synthetic") }, identity, ::JvmAttemptFile)
        fails(PaymentActionException.Storage) { denied.record() }
    }

    @Test fun mount_ownership_is_one_owner_per_identity_until_released() {
        val id = "ownership-" + UUID.randomUUID()
        assertTrue(MountOwnership.acquire(id))
        assertFalse(MountOwnership.acquire(id))
        assertTrue(MountOwnership.acquire("$id/other"))
        MountOwnership.release(id)
        assertTrue(MountOwnership.acquire(id))
        MountOwnership.release(id)
        MountOwnership.release("$id/other")
    }
}
