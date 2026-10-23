package io.meld.sdk.actions

import android.content.Context
import android.util.AtomicFile
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** One order's durable fences. Only a mutation UUID and two flags: no bearer, token, URL or PII. */
internal data class AttemptRecord(
    val submissionKey: UUID = UUID.randomUUID(),
    val submissionStarted: Boolean = false,
    val verificationOpened: Boolean = false,
)

/** Each call reads or writes the record durably and throws [PaymentActionException]; a failure fails closed. */
internal interface AttemptStore {
    fun record(): AttemptRecord

    /** The submission's idempotency key, persisted as started; [PaymentActionException.AlreadyAttempted] the second time. */
    fun claimSubmission(): UUID

    /** Marks verification opened, and so the submission started; [PaymentActionException.AlreadyAttempted] the second time. */
    fun claimVerification()

    /** The server reported an attempt, so this device never offers a new one. */
    fun observeSubmission()
}

/** The bytes of one record file, replaced whole. */
internal interface AttemptFile {
    /** The committed contents, or null when none were ever committed. */
    fun read(): ByteArray?

    fun write(bytes: ByteArray)
}

/** The platform [AtomicFile]: a write is synced, then renamed over the record, so a crash leaves the old or the new one. */
internal class PlatformAttemptFile(file: File) : AttemptFile {
    private val atomic = AtomicFile(file)

    override fun read(): ByteArray? = try {
        atomic.readFully()
    } catch (_: FileNotFoundException) {
        null
    }

    override fun write(bytes: ByteArray) {
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
        } catch (e: IOException) {
            atomic.failWrite(stream)
            throw e
        }
        atomic.finishWrite(stream)
    }
}

/**
 * One file per order, named by the SHA-256 of its identity, under [directory]. In production that is the
 * app's no-backup directory, which the platform never backs up or restores onto another device. Every
 * read-modify-write holds one process-wide lock, so concurrent stores allow exactly one claim; separate
 * processes are not coordinated. Blocking: run it on the actions thread.
 */
internal class FileAttemptStore(
    private val directory: () -> File,
    identity: String,
    private val open: (File) -> AttemptFile = ::PlatformAttemptFile,
) : AttemptStore {
    private val name = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    override fun record(): AttemptRecord = synchronized(lock) { read() }

    override fun claimSubmission(): UUID = update { record ->
        if (record.submissionStarted) throw PaymentActionException.AlreadyAttempted
        record.copy(submissionStarted = true) to record.submissionKey
    }

    override fun claimVerification() = update { record ->
        if (record.verificationOpened) throw PaymentActionException.AlreadyAttempted
        record.copy(submissionStarted = true, verificationOpened = true) to Unit
    }

    override fun observeSubmission() = update { record -> record.copy(submissionStarted = true) to Unit }

    private fun <T> update(change: (AttemptRecord) -> Pair<AttemptRecord, T>): T = synchronized(lock) {
        val (next, result) = change(read())
        write(next)
        result
    }

    private fun read(): AttemptRecord = storage {
        val bytes = open(File(directory(), name)).read() ?: return@storage AttemptRecord()
        decode(bytes) ?: throw PaymentActionException.Storage
    }

    // AtomicFile.finishWrite logs a failed sync or rename instead of throwing, so read the write back.
    private fun write(record: AttemptRecord) = storage {
        val root = directory()
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw PaymentActionException.Storage
        val file = open(File(root, name))
        val bytes = encode(record)
        file.write(bytes)
        if (!bytes.contentEquals(file.read())) throw PaymentActionException.Storage
    }

    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: PaymentActionException) {
        throw e
    } catch (_: IOException) {
        throw PaymentActionException.Storage
    } catch (_: RuntimeException) {
        throw PaymentActionException.Storage
    }

    companion object {
        const val DIRECTORY = "io.meld.sdk.wallet-attempts.v1"
        private val lock = Any()

        /** [identity]'s store in the app's no-backup directory. Touches no disk until first used. */
        fun inNoBackupFiles(context: Context, identity: String): FileAttemptStore {
            val app = context.applicationContext ?: context
            return FileAttemptStore({ File(app.noBackupFilesDir, DIRECTORY) }, identity)
        }

        // The JSON keys are iOS's, so both platforms describe a record the same way.
        private fun encode(record: AttemptRecord): ByteArray = JSONObject()
            .put("submissionKey", record.submissionKey.toString())
            .put("submissionStarted", record.submissionStarted)
            .put("verificationOpened", record.verificationOpened)
            .toString()
            .toByteArray(Charsets.UTF_8)

        private fun decode(bytes: ByteArray): AttemptRecord? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                AttemptRecord(
                    submissionKey = UUID.fromString(json.opt("submissionKey") as? String ?: return null),
                    submissionStarted = strictBoolean(json.opt("submissionStarted")) ?: return null,
                    verificationOpened = strictBoolean(json.opt("verificationOpened")) ?: return null,
                )
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** In-process mount ownership complements the durable submission fence and the server's guard. */
internal object MountOwnership {
    private val active = HashSet<String>()

    @Synchronized
    fun acquire(identity: String): Boolean = active.add(identity)

    @Synchronized
    fun release(identity: String) {
        active.remove(identity)
    }
}
