package com.iptv.player.playback.android

import com.iptv.player.playback.core.PlaybackContentKind
import com.iptv.player.playback.core.PlaybackEndReason
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackQoeRecord
import com.iptv.player.playback.core.PlaybackSession
import com.iptv.player.playback.core.PlaybackSessionId
import com.iptv.player.playback.core.PlaybackTransportKind
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackQoeRuntimeLinkageTest {
    private val key = "b".repeat(64)
    private fun record(
        startedAt: Long,
        endedAt: Long?,
        reason: PlaybackEndReason? = endedAt?.let { PlaybackEndReason.USER_STOP },
    ) = PlaybackQoeRecord(
        session = PlaybackSession(PlaybackSessionId.random(), PlaybackContentKind.LIVE_TV, startedAt,
            PlaybackEngineKind.EXO_PLAYER, PlaybackTransportKind.MPEG_TS),
        finalEngine = PlaybackEngineKind.EXO_PLAYER, endedAtEpochMs = endedAt, endReason = reason, sessionDurationMs = 0,
        timeToReadyMs = null, timeToFirstFrameMs = null, rebufferCount = 0, rebufferDurationMs = 0, engineSwitchCount = 0,
        renderedFrames = 0, droppedFrames = 0, failures = emptyList(), discardedFailureCount = 0, isFinal = endedAt != null,
    )

    @Test fun `cast preview report links only to playback active or ended within two minutes`() {
        val now = 1_000_000L
        val active = record(now - 30_000, null)
        val justEnded = record(now - 60_000, now - 30_000)
        val old = record(now - 600_000, now - 300_000)
        val keys = mapOf(active.session.id to key, justEnded.session.id to key, old.session.id to key)
        assertSame(active, latestReportableSession(listOf(old, justEnded, active), key, keys::get, now))
        assertSame(justEnded, latestReportableSession(listOf(old, justEnded), key, keys::get, now))
        assertNull(latestReportableSession(listOf(old), key, keys::get, now))
        assertNull(latestReportableSession(listOf(active), "c".repeat(64), keys::get, now))
        assertSame(justEnded, latestReportableSession(listOf(justEnded), key, keys::get, now - 30_000 + REPORT_LINK_WINDOW_MS))
        assertNull(latestReportableSession(listOf(justEnded), key, keys::get, now - 30_000 + REPORT_LINK_WINDOW_MS + 1))
    }

    @Test fun `orphaned sessions and other content never receive a report`() {
        val now = 1_000_000L
        val orphan = record(now - 10_000, now, PlaybackEndReason.ABANDONED)
        val other = record(now - 5_000, null)
        val keys = mapOf(orphan.session.id to key, other.session.id to "d".repeat(64))
        assertNull(latestReportableSession(listOf(orphan, other), key, keys::get, now))
    }
}
