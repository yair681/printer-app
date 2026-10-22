package com.yair.printer

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The printer answers two plain-HTTP protocols and needs nothing else, so this
 * is deliberately the whole network layer: no client library, no JSON, no TLS.
 * Its HTTPS port is open but its 2019 handshake is refused by modern clients,
 * which is why every call here is http.
 */
object Net {

    /** What a call came back with. [code] is 0 when the request never landed. */
    data class Reply(
        val code: Int,
        val body: ByteArray,
        val headers: Map<String, String>,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && code in 200..299
        fun text(): String = String(body, Charsets.UTF_8)

        /** Header lookup that ignores case, since servers spell them freely. */
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    fun get(url: String, timeoutMs: Int = 15_000): Reply =
        send(url, "GET", null, null, timeoutMs)

    fun post(url: String, body: ByteArray, contentType: String, timeoutMs: Int = 30_000): Reply =
        send(url, "POST", body, contentType, timeoutMs)

    fun delete(url: String, timeoutMs: Int = 10_000): Reply =
        send(url, "DELETE", null, null, timeoutMs)

    private fun send(
        url: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Int,
    ): Reply {
        var conn: HttpURLConnection? = null
        return try {
            val open = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                if (body != null) {
                    doOutput = true
                    contentType?.let { setRequestProperty("Content-Type", it) }
                    setFixedLengthStreamingMode(body.size)
                }
            }
            conn = open

            body?.let { open.outputStream.use { out -> out.write(it) } }

            val code = open.responseCode
            // A 4xx/5xx body arrives on the error stream, and it usually says why.
            val stream = if (code in 200..299) open.inputStream else open.errorStream
            val bytes = stream?.use { drain(it) } ?: ByteArray(0)

            // headerFields keys are nullable (the status line has none), so the
            // map is built rather than filtered, to keep the key type non-null.
            val headers = buildMap {
                open.headerFields.forEach { (name, values) ->
                    if (name != null) put(name, values.firstOrNull().orEmpty())
                }
            }

            Reply(code, bytes, headers)
        } catch (e: Exception) {
            Reply(0, ByteArray(0), emptyMap(), e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun drain(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
