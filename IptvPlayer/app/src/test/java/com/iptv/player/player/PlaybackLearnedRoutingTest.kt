package com.iptv.player.player

import com.iptv.player.player.PlaybackLearnedRouting.Layer
import com.iptv.player.player.PlaybackRoutingPolicy.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLearnedRoutingTest {

    private var now = 1_000_000L
    private val routing = PlaybackLearnedRouting(clock = { now })

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `nothing learned means the primary engine starts`() {
        assertNull(routing.suggestFor("ch1", Stage.EXO))
        assertNull(routing.deviceDefault())
    }

    @Test
    fun `a rescued channel starts on the rescuing engine next time`() {
        val changes = routing.recordRescue("ch1", "H264|1920|50i|eac3", Stage.EXO, Stage.VLC_HW, Stage.EXO)

        assertTrue(changes.channelLearned)
        assertFalse(changes.fingerprintLearned)
        assertFalse(changes.devicePromoted)
        assertEquals(
            PlaybackLearnedRouting.Suggestion(Stage.VLC_HW, Layer.CHANNEL),
            routing.suggestFor("ch1", Stage.EXO),
        )
        // The learned stage equal to the primary needs no suggestion.
        assertNull(routing.suggestFor("ch1", Stage.VLC_HW))
    }

    @Test
    fun `channel memory expires after its TTL unless confirmed`() {
        routing.recordRescue("ch1", null, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        now += 13 * day
        routing.confirm("ch1", null, Stage.VLC_HW)
        now += 13 * day
        assertEquals(Stage.VLC_HW, routing.suggestFor("ch1", Stage.EXO)?.stage)
        now += 2 * day
        assertNull(routing.suggestFor("ch1", Stage.EXO))
    }

    @Test
    fun `a learned stage that fails again is forgotten at once`() {
        routing.recordRescue("ch1", "fp", Stage.EXO, Stage.VLC_HW, Stage.EXO)

        val dropped = routing.recordLearnedFailure("ch1", "fp", Stage.VLC_HW)

        assertTrue(dropped.channel)
        assertNull(routing.suggestFor("ch1", Stage.EXO))
        // The fingerprint stays remembered for the next layer-2 lookup.
        assertEquals("fp", routing.fingerprintOf("ch1"))
    }

    @Test
    fun `fingerprint rule needs two different channels and then covers a third`() {
        val fp = "H265|1920|50|eac3"
        assertFalse(routing.recordRescue("ch1", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO).fingerprintLearned)
        // The same channel again is not new evidence.
        assertFalse(routing.recordRescue("ch1", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO).fingerprintLearned)
        assertNull(routing.fingerprintStage(fp))

        assertTrue(routing.recordRescue("ch2", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO).fingerprintLearned)
        assertEquals(Stage.VLC_HW, routing.fingerprintStage(fp))

        // A third channel that was only ever seen with this fingerprint starts on VLC.
        routing.rememberFingerprint("ch3", fp)
        assertEquals(
            PlaybackLearnedRouting.Suggestion(Stage.VLC_HW, Layer.FINGERPRINT),
            routing.suggestFor("ch3", Stage.EXO),
        )
        // A channel without a remembered fingerprint gets nothing.
        assertNull(routing.suggestFor("ch4", Stage.EXO))
    }

    @Test
    fun `channel memory wins over the fingerprint rule`() {
        val fp = "H264|1920|50i|ac3"
        routing.recordRescue("ch1", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        routing.recordRescue("ch2", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        // ch3 shares the fingerprint but VLC failed there and EXO rescued it.
        routing.recordRescue("ch3", fp, Stage.VLC_HW, Stage.EXO, Stage.EXO)

        assertEquals(Layer.CHANNEL, routing.suggestFor("ch3", Stage.VLC_HW)?.layer)
        assertEquals(Stage.EXO, routing.suggestFor("ch3", Stage.VLC_HW)?.stage)
        assertNull(routing.suggestFor("ch3", Stage.EXO))
    }

    @Test
    fun `a failed fingerprint rule is dropped but channel votes for the other stage survive`() {
        val fp = "fp"
        routing.recordRescue("ch1", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        routing.recordRescue("ch2", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        assertEquals(Stage.VLC_HW, routing.fingerprintStage(fp))

        val dropped = routing.recordLearnedFailure("ch9", fp, Stage.VLC_HW)

        assertTrue(dropped.fingerprint)
        assertNull(routing.fingerprintStage(fp))
    }

    @Test
    fun `device default moves after three different channels and flips back symmetrically`() {
        assertFalse(routing.recordRescue("ch1", null, Stage.EXO, Stage.VLC_HW, Stage.EXO).devicePromoted)
        assertFalse(routing.recordRescue("ch2", null, Stage.EXO, Stage.VLC_HW, Stage.EXO).devicePromoted)
        assertFalse(routing.recordRescue("ch2", null, Stage.EXO, Stage.VLC_HW, Stage.EXO).devicePromoted)
        assertNull(routing.deviceDefault())

        assertTrue(routing.recordRescue("ch3", null, Stage.EXO, Stage.VLC_HW, Stage.EXO).devicePromoted)
        assertEquals(Stage.VLC_HW, routing.deviceDefault())

        // The learned default now loses three channels to EXO: back to the app default.
        routing.recordRescue("ch4", null, Stage.VLC_HW, Stage.EXO, Stage.VLC_HW)
        routing.recordRescue("ch5", null, Stage.VLC_HW, Stage.EXO, Stage.VLC_HW)
        assertEquals(Stage.VLC_HW, routing.deviceDefault())
        assertTrue(routing.recordRescue("ch6", null, Stage.VLC_HW, Stage.EXO, Stage.VLC_HW).devicePromoted)
        assertEquals(Stage.EXO, routing.deviceDefault())
    }

    @Test
    fun `software rescues and non-primary failures never move the device default`() {
        repeat(5) { routing.recordRescue("sw$it", null, Stage.EXO, Stage.VLC_SW, Stage.EXO) }
        assertNull(routing.deviceDefault())
        // VLC_HW failing while EXO is the primary is not evidence against EXO.
        repeat(5) { routing.recordRescue("np$it", null, Stage.VLC_HW, Stage.EXO, Stage.EXO) }
        assertNull(routing.deviceDefault())
    }

    @Test
    fun `device default expires`() {
        repeat(3) { routing.recordRescue("ch$it", null, Stage.EXO, Stage.VLC_HW, Stage.EXO) }
        assertEquals(Stage.VLC_HW, routing.deviceDefault())
        now += 31 * day
        assertNull(routing.deviceDefault())
    }

    @Test
    fun `a new fingerprint on a known channel invalidates its old rescue`() {
        routing.recordRescue("ch1", "fpA", Stage.EXO, Stage.VLC_HW, Stage.EXO)
        routing.rememberFingerprint("ch1", "fpB")
        assertNull(routing.suggestFor("ch1", Stage.EXO))
        assertEquals("fpB", routing.fingerprintOf("ch1"))
    }

    @Test
    fun `state survives an encode and load round trip`() {
        val fp = "H264|1920|50i|eac3"
        routing.recordRescue("AUTO|AUTO|ch,1=x@y", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        routing.recordRescue("ch2", fp, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        routing.rememberFingerprint("ch3", fp)
        repeat(3) { routing.recordRescue("dev$it", null, Stage.EXO, Stage.VLC_HW, Stage.EXO) }
        val encoded = routing.encode()

        val restored = PlaybackLearnedRouting(clock = { now })
        restored.load(encoded)

        assertEquals(Stage.VLC_HW, restored.suggestFor("AUTO|AUTO|ch_1_x_y", Stage.EXO)?.stage)
        assertEquals(Stage.VLC_HW, restored.fingerprintStage(fp))
        assertEquals(Layer.FINGERPRINT, restored.suggestFor("ch3", Stage.EXO)?.layer)
        assertEquals(Stage.VLC_HW, restored.deviceDefault())
        assertEquals(routing.channelCount, restored.channelCount)
        assertEquals(routing.fingerprintCount, restored.fingerprintCount)
    }

    @Test
    fun `loading garbage or an old format starts empty`() {
        routing.recordRescue("ch1", null, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        routing.load("something-else\nC\tch1\tVLC_HW")
        assertNull(routing.suggestFor("ch1", Stage.EXO))
        routing.load("learned-routing-v1\nC\tbroken\n\nD\tVLC_HW\tnot-a-number\t")
        assertNull(routing.deviceDefault())
        assertEquals(0, routing.channelCount)
    }

    @Test
    fun `channel records are capped by least recent use`() {
        val small = PlaybackLearnedRouting(clock = { now }, maxChannels = 2)
        small.recordRescue("a", null, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        small.recordRescue("b", null, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        // Reading "a" makes it the most recent; "b" is evicted by the third record.
        small.suggestFor("a", Stage.EXO)
        small.recordRescue("c", null, Stage.EXO, Stage.VLC_HW, Stage.EXO)
        assertEquals(2, small.channelCount)
        assertEquals(Stage.VLC_HW, small.suggestFor("a", Stage.EXO)?.stage)
        assertNull(small.suggestFor("b", Stage.EXO))
    }

    @Test
    fun `clear forgets every layer`() {
        routing.recordRescue("ch1", "fp", Stage.EXO, Stage.VLC_HW, Stage.EXO)
        repeat(3) { routing.recordRescue("d$it", null, Stage.EXO, Stage.VLC_HW, Stage.EXO) }
        routing.clear()
        assertNull(routing.suggestFor("ch1", Stage.EXO))
        assertNull(routing.deviceDefault())
        assertEquals(0, routing.channelCount)
    }
}
