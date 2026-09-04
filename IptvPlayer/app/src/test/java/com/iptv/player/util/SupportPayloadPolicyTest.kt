package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPayloadPolicyTest {
    @Test fun `only supported types and bounded messages are accepted`() {
        assertNull(SupportPayloadPolicy.prepare("admin", "hello", null, emptyMap()))
        assertNull(SupportPayloadPolicy.prepare("movie", " ", null, emptyMap()))
        assertNull(SupportPayloadPolicy.prepare("movie", "x".repeat(501), null, emptyMap()))
        assertNull(SupportPayloadPolicy.prepare("movie", "hello", "unexpected log", emptyMap()))
        assertNotNull(SupportPayloadPolicy.prepare("MOVIE", "x".repeat(500), null, emptyMap()))
        assertNotNull(SupportPayloadPolicy.prepare("diagnostic", "x".repeat(5000), "log", emptyMap()))
        assertNull(SupportPayloadPolicy.prepare("diagnostic", "x".repeat(5001), "log", emptyMap()))
    }

    @Test fun `URLs bearer material JSON secrets and account labels are removed`() {
        val original = "https://tv.example/custom-path/alice/s3cr3t/123\n" +
            "username=alice password=s3cr3t secret=top-secret\n" +
            "Bearer abc.def_0123\n{\"secret\":\"private-value\"}\n" +
            "last account alice streaming"
        val safe = SupportPayloadPolicy.sanitize(original, listOf("alice", "s3cr3t"))
        listOf("tv.example", "alice", "s3cr3t", "top-secret", "abc.def_0123", "private-value")
            .forEach { assertFalse("Leaked $it", safe.contains(it)) }
        assertTrue(safe.contains("last account"))
    }

    @Test fun `metadata is an allowlist with strict integral range`() {
        val payload = SupportPayloadPolicy.prepare("complaint", "help", null, mapOf(
            "username" to "alice", "serverUrl" to "https://secret.example", "deviceId" to "android-id",
            "secret" to "shh", "manufacturer" to "M".repeat(80), "model" to "TV",
            "apiLevel" to 25, "versionCode" to 127L, "decoder" to "hardware",
        ))!!
        assertEquals(setOf("manufacturer", "model", "apiLevel", "versionCode", "decoder"), payload.metadata.keys)
        assertEquals(64, (payload.metadata["manufacturer"] as String).length)
        assertEquals(127, payload.metadata["versionCode"])
        val invalid = SupportPayloadPolicy.prepare("complaint", "help", null,
            mapOf("apiLevel" to 25.5, "versionCode" to Long.MAX_VALUE))!!
        assertTrue(invalid.metadata.isEmpty())
    }

    @Test fun `control and direction characters cannot spoof UI or logs`() {
        assertEquals("one\ntwothree", SupportPayloadPolicy.sanitize("one\r\ntwo\u202E\u0000three"))
    }

    @Test fun `log cap is UTF8 bytes and preserves newest whole codepoints`() {
        val payload = SupportPayloadPolicy.prepare("diagnostic", "report", "🎬ö".repeat(30_000) + "END", emptyMap())!!
        val log = payload.log!!
        assertTrue(log.toByteArray(Charsets.UTF_8).size <= SupportPayloadPolicy.MAX_LOG_BYTES)
        assertTrue(log.endsWith("END"))
        assertFalse(log.contains('\uFFFD'))
        assertEquals("", SupportPayloadPolicy.utf8Tail("🎬", 3))
        assertEquals("🎬", SupportPayloadPolicy.utf8Tail("🎬", 4))
        assertEquals("", SupportPayloadPolicy.utf8Tail("abc", 0))
    }

    @Test fun `retry fingerprint is stable and independent of metadata insertion order`() {
        val first = SupportPayloadPolicy.prepare("movie", "Film bitte", null,
            linkedMapOf("model" to "TV", "apiLevel" to 25))!!
        val retry = SupportPayloadPolicy.prepare("movie", "Film bitte", null,
            linkedMapOf("apiLevel" to 25, "model" to "TV"))!!
        assertEquals(first.fingerprint(), retry.fingerprint())
        assertNotEquals(first.fingerprint(), first.copy(message = "Andere Bitte").fingerprint())
        assertNotEquals(first.fingerprint(), first.copy(type = "series").fingerprint())
    }

    @Test fun `unsupported metadata cannot modify retry identity`() {
        val first = SupportPayloadPolicy.prepare("movie", "please", null, emptyMap())!!
        val second = SupportPayloadPolicy.prepare("movie", "please", null,
            mapOf("username" to "alice", "url" to "https://secret.example"))!!
        assertEquals(first.fingerprint(), second.fingerprint())
    }

    @Test fun `valid strict receipt accepts PostgreSQL string ids or integer ids`() {
        val code = "K-0123456789ABCDEF"
        assertEquals(SupportResult.Success(7L, code), SupportPayloadPolicy.receipt(true, "7", code))
        assertNotNull(SupportPayloadPolicy.receipt(true, 7L, code))
        assertNotNull(SupportPayloadPolicy.receipt(true, 7, code))
    }

    @Test fun `HTML loose truth values and malformed receipts never mean success`() {
        val code = "K-0123456789ABCDEF"
        assertNull(SupportPayloadPolicy.receipt("true", 7, code))
        assertNull(SupportPayloadPolicy.receipt(1, 7, code))
        assertNull(SupportPayloadPolicy.receipt(true, 0, code))
        assertNull(SupportPayloadPolicy.receipt(true, 7.5, code))
        assertNull(SupportPayloadPolicy.receipt(true, "007", code))
        assertNull(SupportPayloadPolicy.receipt(true, "9007199254740992", code))
        assertNull(SupportPayloadPolicy.receipt(true, "7", "<html>OK</html>"))
        assertNull(SupportPayloadPolicy.receipt(true, "7", "K-abc"))
        assertNull(SupportPayloadPolicy.receipt(null, null, null))
    }

    @Test fun `HTTP errors expose meaningful status and receipt exposes support code`() {
        assertTrue(SupportResult.Failure(SupportFailureKind.HTTP, 403).userMessage.contains("HTTP 403"))
        assertTrue(SupportResult.Failure(SupportFailureKind.HTTP, 429).userMessage.contains("später"))
        assertTrue(SupportResult.Success(7, "K-0123456789ABCDEF").userMessage.contains("K-0123456789ABCDEF"))
    }

    @Test fun `verified receipt survives UI interruption only for ten minutes`() {
        val receipt = SupportResult.Success(7, "K-0123456789ABCDEF")
        val start = 1_000_000L
        val saved = SupportPayloadPolicy.CachedReceipt(start, receipt).encode()
        assertEquals(receipt, SupportPayloadPolicy.cachedReceipt(saved, start)?.receipt)
        assertNotNull(SupportPayloadPolicy.cachedReceipt(saved, start + SupportPayloadPolicy.RECEIPT_TTL_MS - 1))
        assertNull(SupportPayloadPolicy.cachedReceipt(saved, start + SupportPayloadPolicy.RECEIPT_TTL_MS))
        assertNull(SupportPayloadPolicy.cachedReceipt(saved, start - 1))
        assertNull(SupportPayloadPolicy.cachedReceipt("$start|7|<html>OK</html>", start))
        assertNull(SupportPayloadPolicy.cachedReceipt("$start|0|K-0123456789ABCDEF", start))
        assertNull(SupportPayloadPolicy.cachedReceipt("corrupt", start))
    }
}
