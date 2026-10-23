package io.meld.sdk.actions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** An [HttpURLConnection] that answers from memory and records what it was sent. Never touches a network. */
internal class FakeConnection(
    url: URL,
    private val status: Int = 200,
    private val response: ByteArray? = """{"version":1}""".toByteArray(),
    private val failAt: String? = null,
    private val onRead: () -> Unit = {},
) : HttpURLConnection(url) {
    val headers = linkedMapOf<String, String>()
    val sent = ByteArrayOutputStream()
    var bytesRead = 0
        private set
    var disconnected = 0
        private set
    val fixedLength: Int get() = fixedContentLength

    override fun connect() {
        if (failAt == "connect") throw IOException("synthetic")
        connected = true
    }

    override fun disconnect() {
        disconnected += 1
    }

    override fun usingProxy() = false

    override fun setRequestProperty(key: String, value: String) {
        headers[key] = value
        super.setRequestProperty(key, value)
    }

    override fun getOutputStream(): OutputStream {
        connect()
        if (failAt == "write") throw IOException("synthetic")
        return sent
    }

    override fun getResponseCode(): Int {
        if (failAt == "status") throw IOException("synthetic")
        return status
    }

    override fun getInputStream(): InputStream {
        if (status !in 200..299) throw FileNotFoundException("synthetic")
        return body() ?: throw IOException("no body")
    }

    override fun getErrorStream(): InputStream? = if (status >= 400) body() else null

    private fun body(): InputStream? {
        val bytes = response ?: return null
        return object : InputStream() {
            private val source = ByteArrayInputStream(bytes)

            override fun read(): Int = count(source.read())

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (failAt == "read") throw IOException("synthetic")
                onRead()
                return source.read(b, off, len).also { if (it > 0) bytesRead += it }
            }

            private fun count(value: Int) = value.also { if (it >= 0) bytesRead += 1 }
        }
    }
}
