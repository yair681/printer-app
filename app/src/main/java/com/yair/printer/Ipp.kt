package com.yair.printer

import java.io.ByteArrayOutputStream

/**
 * IPP is an HTTP POST with a binary body, so the request is assembled by hand
 * here rather than pulled in as a dependency. One operation is used,
 * Get-Printer-Attributes, and it is what makes the status screen possible:
 * toner level, state and queue length all come back in a single round trip.
 */
object Ipp {

    /** What the printer reports about itself right now. */
    data class Status(
        val model: String? = null,
        val state: String? = null,
        val stateReasons: String? = null,
        val tonerPercent: Int? = null,
        val tonerName: String? = null,
        val queued: Int? = null,
        val colorSupported: Boolean? = null,
        val error: String? = null,
    ) {
        val reachable: Boolean get() = error == null
    }

    private val WANTED = listOf(
        "printer-state",
        "printer-state-reasons",
        "printer-make-and-model",
        "marker-levels",
        "marker-names",
        "queued-job-count",
        "color-supported",
    )

    fun status(host: String): Status {
        val uri = "ipp://$host/ipp/print"
        val reply = Net.post("http://$host:631/ipp/print", request(uri), "application/ipp")

        if (!reply.ok) {
            return Status(error = reply.error ?: "HTTP ${reply.code}")
        }

        val attrs = parse(reply.body)

        return Status(
            model = attrs["printer-make-and-model"]?.firstOrNull(),
            state = attrs["printer-state"]?.firstOrNull()?.let(::stateName),
            stateReasons = attrs["printer-state-reasons"]?.joinToString(", "),
            tonerPercent = attrs["marker-levels"]?.firstOrNull()?.toIntOrNull(),
            // The name carries a serial after an underscore; the colour is enough.
            tonerName = attrs["marker-names"]?.firstOrNull()?.substringBefore("_"),
            queued = attrs["queued-job-count"]?.firstOrNull()?.toIntOrNull(),
            colorSupported = attrs["color-supported"]?.firstOrNull()?.toBooleanStrictOrNull(),
        )
    }

    /** RFC 8011 printer-state: the only three values it may take. */
    private fun stateName(raw: String): String = when (raw) {
        "3" -> "פנויה"
        "4" -> "מדפיסה"
        "5" -> "עצורה"
        else -> "לא ידוע ($raw)"
    }

    private fun request(printerUri: String): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(byteArrayOf(0x02, 0x00))          // IPP 2.0
        out.writeShort(0x000B)                       // Get-Printer-Attributes
        out.writeInt(1)                              // request-id
        out.write(0x01)                              // operation-attributes-tag

        out.attr(0x47, "attributes-charset", "utf-8")
        out.attr(0x48, "attributes-natural-language", "en")
        out.attr(0x45, "printer-uri", printerUri)

        // The first requested attribute carries the name; the rest are extra
        // values of it, which is how IPP spells a list.
        out.attr(0x44, "requested-attributes", WANTED.first())
        WANTED.drop(1).forEach { out.attr(0x44, "", it) }

        out.write(0x03)                              // end-of-attributes-tag
        return out.toByteArray()
    }

    /**
     * Walks the response far enough to collect every attribute as text. An
     * attribute with no name continues the previous one, so the last name seen
     * is carried forward.
     */
    private fun parse(raw: ByteArray): Map<String, List<String>> {
        val found = linkedMapOf<String, MutableList<String>>()
        var i = 8                                    // past version, status, request-id
        var name = ""

        while (i < raw.size) {
            val tag = raw[i].toInt() and 0xFF

            if (tag in 0x01..0x05) {
                i++
                if (tag == 0x03) break               // end of attributes
                continue
            }

            if (i + 3 > raw.size) break
            val nameLen = raw.short(i + 1)
            val nameAt = i + 3
            if (nameAt + nameLen + 2 > raw.size) break
            if (nameLen > 0) name = String(raw, nameAt, nameLen, Charsets.UTF_8)

            val valueLenAt = nameAt + nameLen
            val valueLen = raw.short(valueLenAt)
            val valueAt = valueLenAt + 2
            if (valueAt + valueLen > raw.size) break
            val value = raw.copyOfRange(valueAt, valueAt + valueLen)
            i = valueAt + valueLen

            if (name.isEmpty()) continue

            val text = when (tag) {
                0x21, 0x23 -> if (value.size == 4) value.int().toString() else ""
                0x22 -> (value.isNotEmpty() && value[0].toInt() != 0).toString()
                else -> String(value, Charsets.UTF_8)
            }

            found.getOrPut(name) { mutableListOf() }.add(text)
        }

        return found
    }

    private fun ByteArrayOutputStream.writeShort(v: Int) {
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.attr(tag: Int, name: String, value: String) {
        val n = name.toByteArray(Charsets.UTF_8)
        val v = value.toByteArray(Charsets.UTF_8)
        write(tag)
        writeShort(n.size)
        write(n)
        writeShort(v.size)
        write(v)
    }

    private fun ByteArray.short(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.int(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
}
