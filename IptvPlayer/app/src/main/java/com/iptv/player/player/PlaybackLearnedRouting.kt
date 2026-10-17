package com.iptv.player.player

import com.iptv.player.player.PlaybackRoutingPolicy.Stage

/**
 * Three-layer autonomous route learning (1.7.0), framework-free.
 *
 * A fallback the viewer has sat through once should never be walked again:
 *
 *  1. Channel memory: engine A failed on a channel for a decoder-class reason
 *     (decode error, invalid/frozen video, stutter, no frame, unsupported
 *     audio) and engine B then played it stably for 15 s -> the channel starts
 *     on B next time.
 *  2. Stream fingerprint memory: when two different channels with the same
 *     [StreamFingerprint] learned the same rescue, every channel with that
 *     fingerprint starts on B (its fingerprint is remembered per channel, and
 *     a channel whose fingerprint becomes known mid-attempt is switched early).
 *  3. Device default: when the effective primary engine lost three different
 *     channels to the same hardware rescue, the AUTO start engine for this
 *     device becomes that engine. The symmetric evidence (the learned default
 *     losing three channels to the other hardware engine) flips it back.
 *
 * Network, HTTP and start-timeout failures never reach this model: the
 * controller only reports rescues after decoder-class failures. A learned
 * stage that fails again before proving stable is dropped at once
 * ([recordLearnedFailure]); every record expires.
 *
 * Keys are opaque route keys (channel id + settings namespace, no URL) and
 * fingerprint keys; stage names are the only other content.
 */
internal class PlaybackLearnedRouting(
    private val clock: () -> Long,
    private val channelTtlMs: Long = CHANNEL_TTL_MS,
    private val fingerprintTtlMs: Long = FINGERPRINT_TTL_MS,
    private val deviceTtlMs: Long = DEVICE_TTL_MS,
    private val fingerprintEvidenceRequired: Int = FINGERPRINT_EVIDENCE,
    private val deviceEvidenceRequired: Int = DEVICE_EVIDENCE,
    private val maxChannels: Int = MAX_CHANNELS,
    private val maxFingerprints: Int = MAX_FINGERPRINTS,
) {

    enum class Layer { CHANNEL, FINGERPRINT, DEVICE }

    data class Suggestion(val stage: Stage, val layer: Layer)

    /** What one rescue changed; for the log/telemetry line. */
    data class Changes(
        val channelLearned: Boolean = false,
        val fingerprintLearned: Boolean = false,
        val devicePromoted: Boolean = false,
    ) {
        val any: Boolean get() = channelLearned || fingerprintLearned || devicePromoted

        override fun toString(): String = buildList {
            if (channelLearned) add("channel")
            if (fingerprintLearned) add("fingerprint")
            if (devicePromoted) add("device")
        }.joinToString(",").ifEmpty { "none" }
    }

    /** What a learned failure dropped. */
    data class Dropped(
        val channel: Boolean = false,
        val fingerprint: Boolean = false,
    ) {
        val any: Boolean get() = channel || fingerprint

        override fun toString(): String = buildList {
            if (channel) add("channel")
            if (fingerprint) add("fingerprint")
        }.joinToString(",").ifEmpty { "none" }
    }

    private class Vote(val stage: Stage, val atMs: Long)

    private class ChannelEntry(
        var stage: Stage?,
        var fingerprint: String?,
        var learnedAtMs: Long,
        var confirmedAtMs: Long,
    )

    private class FingerprintEntry(
        var stage: Stage?,
        var confirmedAtMs: Long,
        val votes: LinkedHashMap<String, Vote> = LinkedHashMap(),
    )

    private class DeviceEntry(
        var stage: Stage?,
        var confirmedAtMs: Long,
        val votes: LinkedHashMap<String, Vote> = LinkedHashMap(),
    )

    private val channels = LinkedHashMap<String, ChannelEntry>()
    private val fingerprints = LinkedHashMap<String, FingerprintEntry>()
    private var device = DeviceEntry(stage = null, confirmedAtMs = 0L)

    // ---- reads ----------------------------------------------------------

    /**
     * The stage this channel should start on instead of [primary], or null when
     * nothing learned differs from it. Layer 1 wins over layer 2; layer 3 is
     * read separately through [deviceDefault] because it changes [primary].
     */
    fun suggestFor(channelKey: String?, primary: Stage): Suggestion? {
        val now = clock()
        val entry = channelKey?.let { channels[it] }
        val channelStage = entry?.stage?.takeIf { fresh(entry.confirmedAtMs, channelTtlMs, now) }
        if (channelStage != null) {
            touch(channelKey)
            return if (channelStage != primary) Suggestion(channelStage, Layer.CHANNEL) else null
        }
        val fingerprintStage = fingerprintStage(entry?.fingerprint)
        if (fingerprintStage != null && fingerprintStage != primary) {
            return Suggestion(fingerprintStage, Layer.FINGERPRINT)
        }
        return null
    }

    /** The learned AUTO start engine for this device, or null (app default). */
    fun deviceDefault(): Stage? =
        device.stage?.takeIf { fresh(device.confirmedAtMs, deviceTtlMs, clock()) }

    /** The stage learned for a fingerprint (layer 2), or null. */
    fun fingerprintStage(fingerprint: String?): Stage? {
        val entry = fingerprint?.let { fingerprints[it] } ?: return null
        return entry.stage?.takeIf { fresh(entry.confirmedAtMs, fingerprintTtlMs, clock()) }
    }

    /** The fingerprint last seen on this channel, if remembered. */
    fun fingerprintOf(channelKey: String?): String? =
        channelKey?.let { channels[it]?.fingerprint }

    // ---- writes ---------------------------------------------------------

    /** Remember which fingerprint a channel carries (layer 2 lookup on the next start). */
    fun rememberFingerprint(channelKey: String?, fingerprint: String?) {
        val key = channelKey ?: return
        val fp = fingerprint ?: return
        val now = clock()
        val entry = channels[key]
        if (entry == null) {
            channels[key] = ChannelEntry(stage = null, fingerprint = fp, learnedAtMs = now, confirmedAtMs = now)
            evictChannels()
        } else if (entry.fingerprint != fp) {
            // A different signature means a different stream: an old rescue
            // learned for the previous signature says nothing about this one.
            entry.fingerprint = fp
            entry.stage = null
        }
    }

    /**
     * Engine [failed] lost this channel for a decoder-class reason and
     * [rescuedBy] then played it stably. [primary] is the effective AUTO start
     * engine right now (device default applied), so layer 3 counts only
     * rescues away from it.
     */
    fun recordRescue(
        channelKey: String?,
        fingerprint: String?,
        failed: Stage,
        rescuedBy: Stage,
        primary: Stage,
    ): Changes {
        if (failed == rescuedBy) return Changes()
        val now = clock()
        var changes = Changes()
        val key = channelKey ?: return changes

        // Layer 1.
        val entry = channels[key]
        if (entry == null) {
            channels[key] = ChannelEntry(rescuedBy, fingerprint, now, now)
            evictChannels()
        } else {
            entry.stage = rescuedBy
            if (fingerprint != null) entry.fingerprint = fingerprint
            entry.learnedAtMs = now
            entry.confirmedAtMs = now
        }
        changes = changes.copy(channelLearned = true)

        // Layer 2: two different channels with the same signature.
        if (fingerprint != null) {
            val fpEntry = fingerprints[fingerprint]
                ?: FingerprintEntry(stage = null, confirmedAtMs = 0L).also {
                    fingerprints[fingerprint] = it
                    evictFingerprints()
                }
            fpEntry.votes.remove(key)
            fpEntry.votes[key] = Vote(rescuedBy, now)
            pruneVotes(fpEntry.votes, fingerprintTtlMs, now)
            val support = fpEntry.votes.values.count { it.stage == rescuedBy }
            if (support >= fingerprintEvidenceRequired && fpEntry.stage != rescuedBy) {
                fpEntry.stage = rescuedBy
                fpEntry.confirmedAtMs = now
                changes = changes.copy(fingerprintLearned = true)
            } else if (fpEntry.stage == rescuedBy) {
                fpEntry.confirmedAtMs = now
            }
        }

        // Layer 3: the primary engine lost three different channels to the
        // other hardware engine. Software never becomes a device default.
        if (failed == primary && rescuedBy != primary && isHardware(failed) && isHardware(rescuedBy)) {
            device.votes.remove(key)
            device.votes[key] = Vote(rescuedBy, now)
            pruneVotes(device.votes, deviceTtlMs, now)
            val support = device.votes.values.count { it.stage == rescuedBy }
            if (support >= deviceEvidenceRequired) {
                device.stage = rescuedBy
                device.confirmedAtMs = now
                device.votes.clear()
                changes = changes.copy(devicePromoted = true)
            }
        }
        return changes
    }

    /**
     * A learned [stage] failed again on this channel before proving stable:
     * forget the channel record and, when the fingerprint rule pointed at the
     * same stage, that rule too. The device default needs three counter
     * rescues ([recordRescue] with the learned stage as [primary]) to flip.
     */
    fun recordLearnedFailure(channelKey: String?, fingerprint: String?, stage: Stage): Dropped {
        var dropped = Dropped()
        val entry = channelKey?.let { channels[it] }
        if (entry?.stage == stage) {
            entry.stage = null
            dropped = dropped.copy(channel = true)
        }
        val fp = fingerprint ?: entry?.fingerprint
        val fpEntry = fp?.let { fingerprints[it] }
        if (fpEntry?.stage == stage) {
            fpEntry.stage = null
            fpEntry.votes.values.removeAll { it.stage == stage }
            dropped = dropped.copy(fingerprint = true)
        }
        return dropped
    }

    /** A suggested [stage] played stably again: keep its records alive. */
    fun confirm(channelKey: String?, fingerprint: String?, stage: Stage) {
        val now = clock()
        val entry = channelKey?.let { channels[it] }
        if (entry?.stage == stage) entry.confirmedAtMs = now
        val fp = fingerprint ?: entry?.fingerprint
        val fpEntry = fp?.let { fingerprints[it] }
        if (fpEntry?.stage == stage) fpEntry.confirmedAtMs = now
        if (device.stage == stage) device.confirmedAtMs = now
    }

    fun clear() {
        channels.clear()
        fingerprints.clear()
        device = DeviceEntry(stage = null, confirmedAtMs = 0L)
    }

    val channelCount: Int get() = channels.size
    val fingerprintCount: Int get() = fingerprints.size

    // ---- persistence (tab-separated lines; no JSON so JVM tests cover it) --

    fun encode(): String {
        val sb = StringBuilder()
        sb.append(FORMAT).append('\n')
        channels.forEach { (key, e) ->
            sb.append("C\t").append(safe(key)).append('\t').append(stageName(e.stage)).append('\t')
                .append(e.fingerprint?.let(::safe) ?: "-").append('\t')
                .append(e.learnedAtMs).append('\t').append(e.confirmedAtMs).append('\n')
        }
        fingerprints.forEach { (fp, e) ->
            sb.append("F\t").append(safe(fp)).append('\t').append(stageName(e.stage)).append('\t')
                .append(e.confirmedAtMs).append('\t').append(encodeVotes(e.votes)).append('\n')
        }
        sb.append("D\t").append(stageName(device.stage)).append('\t').append(device.confirmedAtMs)
            .append('\t').append(encodeVotes(device.votes)).append('\n')
        return sb.toString()
    }

    /** Replaces the state; malformed lines are skipped, expired records dropped. */
    fun load(encoded: String?) {
        clear()
        if (encoded.isNullOrBlank()) return
        val now = clock()
        val lines = encoded.split('\n')
        if (lines.firstOrNull()?.trim() != FORMAT) return
        lines.drop(1).forEach { line ->
            val f = line.split('\t')
            runCatching {
                when (f.getOrNull(0)) {
                    "C" -> if (f.size >= 6) {
                        val confirmedAt = f[5].toLong()
                        val stage = stageOf(f[2])?.takeIf { fresh(confirmedAt, channelTtlMs, now) }
                        val fp = f[3].takeIf { it != "-" }
                        if ((stage != null || fp != null) && channels.size < maxChannels) {
                            channels[f[1]] = ChannelEntry(stage, fp, f[4].toLong(), confirmedAt)
                        }
                    }
                    "F" -> if (f.size >= 5) {
                        val confirmedAt = f[3].toLong()
                        val entry = FingerprintEntry(
                            stage = stageOf(f[2])?.takeIf { fresh(confirmedAt, fingerprintTtlMs, now) },
                            confirmedAtMs = confirmedAt,
                        )
                        decodeVotes(f[4], entry.votes)
                        pruneVotes(entry.votes, fingerprintTtlMs, now)
                        if ((entry.stage != null || entry.votes.isNotEmpty()) && fingerprints.size < maxFingerprints) {
                            fingerprints[f[1]] = entry
                        }
                    }
                    "D" -> if (f.size >= 4) {
                        val confirmedAt = f[2].toLong()
                        device = DeviceEntry(
                            stage = stageOf(f[1])?.takeIf { fresh(confirmedAt, deviceTtlMs, now) },
                            confirmedAtMs = confirmedAt,
                        )
                        decodeVotes(f[3], device.votes)
                        pruneVotes(device.votes, deviceTtlMs, now)
                    }
                }
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun fresh(atMs: Long, ttlMs: Long, nowMs: Long): Boolean = atMs > 0L && nowMs - atMs in 0..ttlMs

    private fun isHardware(stage: Stage): Boolean = stage == Stage.EXO || stage == Stage.VLC_HW

    private fun touch(channelKey: String?) {
        val key = channelKey ?: return
        // LinkedHashMap keeps insertion order; re-insert to make this the newest.
        val entry = channels.remove(key) ?: return
        channels[key] = entry
    }

    private fun evictChannels() {
        while (channels.size > maxChannels) {
            val oldest = channels.keys.firstOrNull() ?: break
            channels.remove(oldest)
        }
    }

    private fun evictFingerprints() {
        while (fingerprints.size > maxFingerprints) {
            val oldest = fingerprints.keys.firstOrNull() ?: break
            fingerprints.remove(oldest)
        }
    }

    private fun pruneVotes(votes: MutableMap<String, Vote>, ttlMs: Long, nowMs: Long) {
        votes.values.removeAll { !fresh(it.atMs, ttlMs, nowMs) }
    }

    private fun encodeVotes(votes: Map<String, Vote>): String =
        votes.entries.joinToString(",") { (key, vote) -> "${safe(key)}=${vote.stage.name}@${vote.atMs}" }

    private fun decodeVotes(encoded: String, into: MutableMap<String, Vote>) {
        if (encoded.isBlank()) return
        encoded.split(',').forEach { item ->
            val key = item.substringBefore('=', "")
            val rest = item.substringAfter('=', "")
            val stage = stageOf(rest.substringBefore('@')) ?: return@forEach
            val at = rest.substringAfter('@', "").toLongOrNull() ?: return@forEach
            if (key.isNotEmpty()) into[key] = Vote(stage, at)
        }
    }

    private fun stageName(stage: Stage?): String = stage?.name ?: "-"

    private fun stageOf(name: String): Stage? = Stage.entries.firstOrNull { it.name == name }

    /** Keys are opaque; strip the separators of this format. */
    private fun safe(value: String): String = buildString(value.length) {
        value.forEach { c -> append(if (c == '\t' || c == '\n' || c == '\r' || c == ',' || c == '=' || c == '@') '_' else c) }
    }

    companion object {
        private const val FORMAT = "learned-routing-v1"
        const val CHANNEL_TTL_MS = 14L * 24 * 60 * 60 * 1000
        const val FINGERPRINT_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val DEVICE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        /** Different channels with the same fingerprint before the rule applies. */
        const val FINGERPRINT_EVIDENCE = 2
        /** Different channels lost by the primary engine before the device default moves. */
        const val DEVICE_EVIDENCE = 3
        const val MAX_CHANNELS = 500
        const val MAX_FINGERPRINTS = 200
    }
}
