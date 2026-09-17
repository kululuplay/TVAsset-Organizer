/*
 * VodKeyDispatchPolicy.kt
 *
 * Architecture note (VOD player decomposition):
 *   Pure, JVM-testable decision table behind VodPlayerActivity.dispatchKeyEvent.
 *   The Activity feeds it the key event plus the modal state (next-episode
 *   prompt, terminal error card, control visibility, seek-bar focus, playing) and
 *   executes the returned [Decision]: reveal controls before/after, run one
 *   [Action], and either consume the event or forward it to the platform.
 *   The ordering rules mirror the original inline code exactly (media keys
 *   reveal first, the "hidden controls" reveal happens after the seek).
 */
package com.iptv.player.ui.player

import android.view.KeyEvent

internal object VodKeyDispatchPolicy {

    enum class Reveal { NONE, BEFORE, AFTER }

    sealed class Action {
        object None : Action()
        object CancelNextEpisode : Action()
        object PlayNextEpisode : Action()
        object Retry : Action()
        object TogglePlayPause : Action()
        data class SeekBy(val deltaMs: Long) : Action()
        /** Reveal, move focus onto the seek bar, then seek. */
        data class SeekFromBar(val deltaMs: Long) : Action()
    }

    data class Decision(
        /** true = the Activity returns true; false = forward to super. */
        val consume: Boolean,
        val action: Action = Action.None,
        val reveal: Reveal = Reveal.NONE,
    )

    data class Input(
        val keyCode: Int,
        val isDown: Boolean,
        val repeatCount: Int,
        val nextEpisodeVisible: Boolean,
        val errorVisible: Boolean,
        val controlsVisible: Boolean,
        val seekBarFocused: Boolean,
        val playing: Boolean,
        val skipMs: Long,
    )

    fun isSystemKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    fun decide(input: Input): Decision {
        val keyCode = input.keyCode
        val isSystemKey = isSystemKey(keyCode)

        // Consume both DOWN and UP. Passing only the UP event to Activity can
        // still finish the screen on vendor TV key dispatchers.
        if (input.nextEpisodeVisible && keyCode == KeyEvent.KEYCODE_BACK) {
            return Decision(
                consume = true,
                action = if (input.isDown && input.repeatCount == 0) {
                    Action.CancelNextEpisode
                } else {
                    Action.None
                },
            )
        }

        if (input.isDown) {
            if (input.nextEpisodeVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_HEADSETHOOK -> return Decision(
                        consume = true,
                        action = if (input.repeatCount == 0) Action.PlayNextEpisode else Action.None,
                    )
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> return Decision(consume = true)
                }
            }
            if (input.errorVisible) {
                // Terminal playback state has exactly two exits: Retry or Back.
                // Map hardware play keys to Retry and swallow transport keys so a
                // broken native player cannot be controlled behind the modal card.
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_HEADSETHOOK -> return Decision(
                        consume = true,
                        action = if (input.repeatCount == 0) Action.Retry else Action.None,
                    )
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> return Decision(consume = true)
                }
            }

            // Dedicated media keys must work even while the visual controls are
            // hidden. This matters on full-size TV remotes and Bluetooth remotes.
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> return Decision(
                    consume = true,
                    // Long-press repeats must not alternate play/pause rapidly.
                    action = if (input.repeatCount == 0) Action.TogglePlayPause else Action.None,
                    reveal = Reveal.BEFORE,
                )
                KeyEvent.KEYCODE_MEDIA_PLAY -> return Decision(
                    consume = true,
                    action = if (!input.playing) Action.TogglePlayPause else Action.None,
                    reveal = Reveal.BEFORE,
                )
                KeyEvent.KEYCODE_MEDIA_PAUSE -> return Decision(
                    consume = true,
                    action = if (input.playing) Action.TogglePlayPause else Action.None,
                    reveal = Reveal.BEFORE,
                )
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return Decision(
                    consume = true,
                    action = Action.SeekBy(SeekTimeline.step(input.repeatCount)),
                    reveal = Reveal.BEFORE,
                )
                KeyEvent.KEYCODE_MEDIA_REWIND -> return Decision(
                    consume = true,
                    action = Action.SeekBy(-SeekTimeline.step(input.repeatCount)),
                    reveal = Reveal.BEFORE,
                )
            }

            val horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (horizontal && (!input.controlsVisible || input.seekBarFocused) &&
                !input.errorVisible &&
                !input.nextEpisodeVisible
            ) {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                return Decision(
                    consume = true,
                    action = Action.SeekFromBar(direction * SeekTimeline.step(input.repeatCount)),
                    reveal = Reveal.BEFORE,
                )
            }
            if (!isSystemKey && !input.controlsVisible) {
                // Left/right are natural 10-second seek shortcuts on TV. Other
                // keys reveal the overlay without accidentally activating the
                // previously-focused (currently invisible) control.
                val action = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> Action.SeekBy(-input.skipMs)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> Action.SeekBy(input.skipMs)
                    else -> Action.None
                }
                return Decision(consume = true, action = action, reveal = Reveal.AFTER)
            }
        }
        return Decision(
            consume = false,
            reveal = if (!isSystemKey) Reveal.BEFORE else Reveal.NONE,
        )
    }
}
