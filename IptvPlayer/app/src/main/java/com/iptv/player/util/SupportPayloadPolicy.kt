package com.iptv.player.util

import java.security.MessageDigest
import java.util.Locale

/** Pure, deliberately small privacy/contract boundary for the dedicated support service. */
internal object SupportPayloadPolicy {
    const val MAX_LOG_BYTES = 128 * 1024
    private val types = setOf("diagnostic", "channel", "movie", "series", "complaint")
    private val textFields = mapOf(
        "manufacturer" to 64, "model" to 96, "androidVersion" to 32,
        "appVersion" to 32, "engine" to 32, "transport" to 32,
        "decoder" to 32, "buffer" to 32,
    )
    private val url = Regex("""(?i)https?://[^\s\"']+""")
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val namedSecret = Regex(
        """(?i)\b(username|user|password|passwd|pass|token|secret|api[_-]?key)\s*[:=]\s*[^\s,;]+""",
    )
    private val jsonSecret = Regex(
        """(?i)("(?:secret|authorization|cookie)"\s*:\s*")[^"]*(")""",
    )

    data class Payload(
        val type: String,
        val message: String,
        val log: String?,
        val metadata: Map<String, Any>,
    ) {
        // Length prefixes avoid ambiguous concatenations; metadata order never changes the key.
        fun fingerprint(): String = sha256(buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            field(type); field(message); field(log.orEmpty())
            metadata.toSortedMap().forEach { (key, value) -> field(key); field(value.toString()) }
        })
    }

    fun prepare(
        type: String,
        message: String,
        log: String?,
        metadata: Map<String, Any?>,
        knownSecrets: List<String> = emptyList(),
    ): Payload? {
        val safeType = type.lowercase(Locale.ROOT)
        if (safeType !in types || (log != null && safeType != "diagnostic")) return null
        val maxMessage = if (safeType == "diagnostic") 5000 else 500
        if (message.length > maxMessage) return null
        val safeMessage = sanitize(message, knownSecrets).trim()
        if (safeMessage.isEmpty() || safeMessage.length > maxMessage) return null
        val safeMetadata = linkedMapOf<String, Any>()
        textFields.forEach { (key, max) ->
            (metadata[key] as? String)?.let {
                safeMetadata[key] = sanitize(it, knownSecrets).take(max)
            }
        }
        listOf("apiLevel", "versionCode").forEach { key ->
            val value = metadata[key]
            val integral = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
            if (integral != null && integral in 1..Int.MAX_VALUE.toLong()) {
                safeMetadata[key] = integral.toInt()
            }
        }
        return Payload(
            safeType, safeMessage,
            log?.let { utf8Tail(sanitize(it, knownSecrets), MAX_LOG_BYTES) },
            safeMetadata,
        )
    }

    fun sanitize(value: String, knownSecrets: List<String> = emptyList()): String {
        // Full URLs are not useful to support and can contain credentials in arbitrary paths.
        var text = SensitiveDataRedactor.redact(value)
            .replace(url, "<url removed>")
            .replace(bearer, "Bearer <redacted>")
            .replace(jsonSecret) { "${it.groupValues[1]}<redacted>${it.groupValues[2]}" }
            .replace(namedSecret) { "${it.groupValues[1]}=<redacted>" }
        knownSecrets.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
            .forEach { text = text.replace(it, "<redacted>", ignoreCase = true) }
        return text.replace("\r\n", "\n").replace('\r', '\n').filter {
            (it == '\n' || it == '\t' || !it.isISOControl()) &&
                it != '\u061C' && it != '\u200E' && it != '\u200F' &&
                it !in '\u202A'..'\u202E' && it !in '\u2066'..'\u2069'
        }
    }

    /** Keep the newest log data, but never split a UTF-8 code point at the byte cap. */
    internal fun utf8Tail(value: String, maxBytes: Int): String {
        require(maxBytes >= 0)
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var start = bytes.size - maxBytes
        while (start < bytes.size && (bytes[start].toInt() and 0xc0) == 0x80) start++
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    internal fun receipt(ok: Any?, id: Any?, code: Any?): SupportResult.Success? {
        if (ok != true || code !is String || !Regex("K-[A-F0-9]{16}").matches(code)) return null
        val numericId = when (id) {
            is Long -> id
            is Int -> id.toLong()
            is String -> id.takeIf { Regex("[1-9][0-9]{0,15}").matches(it) }?.toLongOrNull()
            else -> null
        } ?: return null
        if (numericId !in 1..9_007_199_254_740_991L) return null
        return SupportResult.Success(numericId, code)
    }

    internal const val RECEIPT_TTL_MS = 10 * 60 * 1000L
    internal data class CachedReceipt(val recordedAt: Long, val receipt: SupportResult.Success) {
        fun encode(): String = "$recordedAt|${receipt.id}|${receipt.code}"
    }

    internal fun cachedReceipt(raw: String?, now: Long): CachedReceipt? {
        val parts = raw?.split('|') ?: return null
        if (parts.size != 3) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        // A corrected device clock cannot make a receipt live indefinitely.
        if (timestamp < 0 || now < timestamp || now - timestamp >= RECEIPT_TTL_MS) return null
        return receipt(true, parts[1], parts[2])?.let { CachedReceipt(timestamp, it) }
    }

    internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
