package com.yair.printer

/**
 * eSCL, the protocol behind AirScan and Mopria Scan. The printer serves it on
 * plain HTTP with no authentication, which is what lets this app scan straight
 * to the phone with no vendor software anywhere in the path.
 *
 * Flow: POST settings to /eSCL/ScanJobs, read the job URL out of the Location
 * header, then GET <job>/NextDocument until it stops handing pages over.
 */
object Escl {

    /**
     * Where the paper is.
     *
     * [wire] is the trap of this whole protocol. The capabilities document calls
     * the feeder "Adf", so that is the obvious thing to send back - and the
     * printer then quietly scans the glass instead, handing over a blank page
     * while reporting success. `pwg:InputSource` only ever accepts "Platen",
     * "Feeder" or "Camera".
     */
    enum class Source(val label: String, val wire: String) {
        PLATEN("משטח הזכוכית", "Platen"),
        FEEDER("מזין הדפים", "Feeder"),
    }

    enum class Mode(val label: String, val wire: String) {
        COLOR("צבע", "RGB24"),
        GRAY("גווני אפור", "Grayscale8"),
        MONO("שחור-לבן", "BlackAndWhite1"),
    }

    enum class Format(val label: String, val mime: String, val ext: String) {
        PDF("PDF", "application/pdf", "pdf"),
        JPEG("JPEG", "image/jpeg", "jpg"),
    }

    /** What the scanner says it can do, read rather than assumed. */
    data class Capabilities(
        val model: String? = null,
        val serial: String? = null,
        val firmware: String? = null,
        val hasFeeder: Boolean = false,
        val platenWidth: Int = 2550,
        val platenHeight: Int = 3507,
        val feederWidth: Int = 2550,
        val feederHeight: Int = 3507,
        val resolutions: List<Int> = listOf(100, 200, 300),
        val error: String? = null,
    )

    /** Idle or busy, and whether paper is sitting in the feeder. */
    data class State(
        val state: String? = null,
        val feederLoaded: Boolean = false,
        val error: String? = null,
    )

    data class Settings(
        val source: Source = Source.PLATEN,
        val mode: Mode = Mode.COLOR,
        val dpi: Int = 300,
        val format: Format = Format.PDF,
    )

    /** One scanned document, exactly as the printer produced it. */
    data class Page(val bytes: ByteArray, val format: Format)

    fun capabilities(host: String): Capabilities {
        val reply = Net.get("http://$host/eSCL/ScannerCapabilities")
        if (!reply.ok) return Capabilities(error = reply.error ?: "HTTP ${reply.code}")

        val xml = reply.text()
        val platen = section(xml, "Platen") ?: xml
        // The feeder declares its own maximum, which need not match the glass.
        val feeder = section(xml, "Adf")

        return Capabilities(
            model = tag(xml, "MakeAndModel"),
            serial = tag(xml, "SerialNumber"),
            firmware = tag(xml, "Version"),
            hasFeeder = feeder != null,
            platenWidth = number(platen, "MaxWidth") ?: 2550,
            platenHeight = number(platen, "MaxHeight") ?: 3507,
            feederWidth = feeder?.let { number(it, "MaxWidth") } ?: number(platen, "MaxWidth") ?: 2550,
            feederHeight = feeder?.let { number(it, "MaxHeight") } ?: number(platen, "MaxHeight") ?: 3507,
            resolutions = Regex("""<(?:\w+:)?XResolution>\s*(\d+)\s*</(?:\w+:)?XResolution>""")
                .findAll(xml)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .distinct()
                .sorted()
                .toList()
                .ifEmpty { listOf(100, 200, 300) },
        )
    }

    fun state(host: String): State {
        val reply = Net.get("http://$host/eSCL/ScannerStatus", timeoutMs = 8_000)
        if (!reply.ok) return State(error = reply.error ?: "HTTP ${reply.code}")

        val xml = reply.text()
        return State(
            state = tag(xml, "State"),
            feederLoaded = tag(xml, "AdfState")?.contains("Loaded") == true,
        )
    }

    /**
     * Runs one scan and returns every document it produced. A feeder job yields
     * as many as there were sheets; the glass yields one.
     */
    fun scan(host: String, settings: Settings, caps: Capabilities): Result<List<Page>> {
        val body = settingsXml(settings, caps).toByteArray(Charsets.UTF_8)
        var reply = Net.post("http://$host/eSCL/ScanJobs", body, "text/xml; charset=utf-8")

        // 503 is the scanner saying "busy", not "broken": one retry is enough
        // for a previous job that is still being closed.
        if (reply.code == 503) {
            Thread.sleep(1_500)
            reply = Net.post("http://$host/eSCL/ScanJobs", body, "text/xml; charset=utf-8")
        }

        if (reply.code != 201) {
            val why = reply.error ?: "המדפסת החזירה HTTP ${reply.code}"
            return Result.failure(IllegalStateException(why))
        }

        val location = reply.header("Location")
            ?: return Result.failure(IllegalStateException("המדפסת לא החזירה כתובת לעבודה"))
        val job = if (location.startsWith("/")) "http://$host$location" else location

        val pages = mutableListOf<Page>()
        // A feeder can hold a tall stack; the cap only stops a runaway loop.
        // The exit is the printer refusing the next document, so this must
        // break out rather than skip an iteration.
        for (sheet in 0 until 50) {
            val doc = Net.get("$job/NextDocument", timeoutMs = 180_000)
            if (!doc.ok || doc.body.isEmpty()) break
            pages += Page(doc.body, settings.format)
        }

        Net.delete(job)

        return if (pages.isEmpty()) {
            Result.failure(
                IllegalStateException(
                    if (settings.source == Source.FEEDER) {
                        "לא נסרק כלום — אין דפים במזין"
                    } else {
                        "לא נסרק כלום — העבודה בוטלה או שהזכוכית ריקה"
                    }
                )
            )
        } else {
            Result.success(pages)
        }
    }

    private fun settingsXml(s: Settings, caps: Capabilities): String {
        val width = if (s.source == Source.FEEDER) caps.feederWidth else caps.platenWidth
        val height = if (s.source == Source.FEEDER) caps.feederHeight else caps.platenHeight
        return xml(s, width, height)
    }

    private fun xml(s: Settings, width: Int, height: Int): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03"
                           xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
          <pwg:Version>2.5</pwg:Version>
          <pwg:ScanRegions>
            <pwg:ScanRegion>
              <pwg:XOffset>0</pwg:XOffset>
              <pwg:YOffset>0</pwg:YOffset>
              <pwg:Width>$width</pwg:Width>
              <pwg:Height>$height</pwg:Height>
              <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
            </pwg:ScanRegion>
          </pwg:ScanRegions>
          <pwg:InputSource>${s.source.wire}</pwg:InputSource>
          <pwg:DocumentFormat>${s.format.mime}</pwg:DocumentFormat>
          <scan:ColorMode>${s.mode.wire}</scan:ColorMode>
          <scan:XResolution>${s.dpi}</scan:XResolution>
          <scan:YResolution>${s.dpi}</scan:YResolution>
        </scan:ScanSettings>
    """.trimIndent()

    private fun tag(xml: String, name: String): String? =
        Regex("""<(?:\w+:)?$name>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private fun number(xml: String, name: String): Int? = tag(xml, name)?.toIntOrNull()

    private fun section(xml: String, name: String): String? =
        Regex("""<(?:\w+:)?$name>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
}
