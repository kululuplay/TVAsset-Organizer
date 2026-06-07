/*
 * XmltvParser.kt
 * Streaming parser for XMLTV guide dumps (Xtream's xmltv.php or any external
 * EPG URL). Uses XmlPullParser to keep memory low on weak devices — programs
 * are emitted into a callback rather than held all in memory.
 *
 * Expected shape:
 *   <tv>
 *     <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="id">
 *       <title>...</title>
 *       <desc>...</desc>
 *     </programme>
 *   </tv>
 */
package com.iptv.player.data.parser

import android.util.Xml
import com.iptv.player.data.model.Program
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

object XmltvParser {

    /**
     * Parse [input], invoking [onChannel] for each <channel> id/display-name and
     * [onProgram] for each <programme>. [onChannel] lets callers build a name →
     * id fallback map for channels whose tvg-id doesn't line up with the guide.
     */
    fun parse(
        input: InputStream,
        onChannel: (id: String, displayName: String) -> Unit = { _, _ -> },
        onProgram: (Program) -> Unit
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channel: String? = null
        var startMs = 0L
        var stopMs = 0L
        var title: String? = null
        var desc: String? = null
        var current: String? = null

        // <channel id="..."><display-name>..</display-name></channel> context.
        var chanId: String? = null
        var chanName: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            chanId = parser.getAttributeValue(null, "id")
                            chanName = null
                        }
                        "programme" -> {
                            channel = parser.getAttributeValue(null, "channel")
                            startMs = parseTime(parser.getAttributeValue(null, "start"))
                            stopMs = parseTime(parser.getAttributeValue(null, "stop"))
                            title = null
                            desc = null
                        }
                        "display-name" -> {
                            // Only capture the first display-name of a channel.
                            if (chanId != null && chanName == null) current = "display-name"
                        }
                        "title", "desc" -> current = parser.name
                    }
                }
                XmlPullParser.TEXT -> {
                    when (current) {
                        "title" -> title = (title ?: "") + parser.text
                        "desc" -> desc = (desc ?: "") + parser.text
                        "display-name" -> chanName = (chanName ?: "") + parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title", "desc", "display-name" -> current = null
                        "channel" -> {
                            val id = chanId
                            val nm = chanName?.trim()
                            if (id != null && !nm.isNullOrBlank()) onChannel(id, nm)
                            chanId = null
                            chanName = null
                        }
                        "programme" -> {
                            val ch = channel
                            if (ch != null && stopMs > startMs && !title.isNullOrBlank()) {
                                onProgram(
                                    Program(
                                        epgChannelId = ch,
                                        title = title!!.trim(),
                                        description = desc?.trim(),
                                        startMs = startMs,
                                        stopMs = stopMs
                                    )
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    // XMLTV time is "yyyyMMddHHmmss Z"; the offset may be missing or attached
    // without a separating space (e.g. "20240101120000+0300").
    private val withZone = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val noZone = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val value = raw.trim()
        return try {
            // Split off a trailing +HHMM / -HHMM timezone offset, with or without
            // a separating space, so a glued offset doesn't make the whole parse
            // fail (which would drop the program entirely).
            val sign = value.indexOfFirst { it == '+' || it == '-' }
            if (sign in 1 until value.length) {
                val datePart = value.substring(0, sign).trim()
                val zonePart = value.substring(sign).trim()
                withZone.parse("$datePart $zonePart")?.time ?: 0L
            } else {
                noZone.parse(value)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
