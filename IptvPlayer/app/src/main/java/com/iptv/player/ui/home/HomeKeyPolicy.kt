/*
 * HomeKeyPolicy.kt
 *
 * Architecture note (Home screen decomposition):
 *   Pure, JVM-testable remote-key rules of HomeActivity:
 *   - [ConsumedUntilUpTracker]: after a pre-dispatch key action, swallow the
 *     key's repeats and its matching UP (a fresh DOWN means the UP went to
 *     another window, e.g. a PIN dialog, and must pass through).
 *   - [HomeKeyCodes]: confirm / number / zap-cancelling key classification.
 *   - [HomeFullscreenKeyPolicy]: which non-confirm keys the inline fullscreen
 *     player (and its channel guide) consumes and what command they map to.
 *   The Activity executes the commands; nothing here touches playback.
 *
 * Extracted from HomeActivity.dispatchKeyEvent / dispatchFullscreenKey /
 * dispatchFullscreenGuideKey without changing the decision table.
 */
package com.iptv.player.ui.home

import android.view.KeyEvent

/** Swallows repeats and matching UP after a pre-dispatch key action. */
internal class ConsumedUntilUpTracker {
    private val keys = mutableSetOf<Int>()

    fun add(keyCode: Int) {
        keys.add(keyCode)
    }

    fun clear() {
        keys.clear()
    }

    /**
     * True when the event must be consumed without dispatch. A fresh DOWN means
     * the matching UP went to another window (a PIN dialog opened by the consumed
     * press); the stale entry is dropped and the press passes through.
     */
    fun swallow(keyCode: Int, isDown: Boolean, isUp: Boolean, repeatCount: Int): Boolean {
        if (keyCode !in keys) return false
        if (isDown && repeatCount == 0) {
            keys.remove(keyCode)
            return false
        }
        if (isUp) keys.remove(keyCode)
        return true
    }
}

internal object HomeKeyCodes {
    fun isConfirmKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A -> true
        else -> false
    }

    fun isNumberKey(keyCode: Int): Boolean =
        keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9

    fun cancelsNumberZap(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_PROG_RED -> true
        else -> false
    }
}

internal object HomeFullscreenKeyPolicy {

    enum class Command {
        NONE,
        EXIT_FULLSCREEN,
        ZAP_UP,
        ZAP_DOWN,
        MENU,
        TOGGLE_CAPTION,
        CATCHUP,
        CLOSE_GUIDE,
        GUIDE_ZAP_UP,
        GUIDE_ZAP_DOWN,
        GUIDE_MENU,
        GUIDE_CATCHUP,
    }

    data class Decision(
        /** false = not a fullscreen key; let the platform focus search handle it. */
        val consume: Boolean,
        /** Cancel a pending CH+/- debounce before running the command. */
        val cancelZap: Boolean = false,
        val command: Command = Command.NONE,
        /** Swallow the key's repeats/UP after the command (BACK-like keys). */
        val markConsumedUntilUp: Boolean = false,
    )

    private val NOT_HANDLED = Decision(consume = false)
    private val CONSUME_ONLY = Decision(consume = true)

    /** Non-confirm keys only; confirm keys are matched by RemoteConfirmPress in the Activity. */
    fun decide(keyCode: Int, isDown: Boolean, repeatCount: Int, guideVisible: Boolean): Decision =
        if (guideVisible) decideGuide(keyCode, isDown, repeatCount)
        else decidePlayer(keyCode, isDown, repeatCount)

    /**
     * Fullscreen remains inside HomeActivity, so every handled remote event is
     * consumed before Android's focus search can escape into the hidden browser.
     */
    private fun decidePlayer(keyCode: Int, isDown: Boolean, repeatCount: Int): Decision {
        val handledKey = when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> true
            else -> false
        }
        if (!handledKey) return NOT_HANDLED
        if (!isDown || repeatCount > 0) return CONSUME_ONLY
        val cancelZap =
            keyCode != KeyEvent.KEYCODE_DPAD_UP &&
                keyCode != KeyEvent.KEYCODE_DPAD_DOWN &&
                keyCode != KeyEvent.KEYCODE_CHANNEL_UP &&
                keyCode != KeyEvent.KEYCODE_CHANNEL_DOWN &&
                keyCode != KeyEvent.KEYCODE_PAGE_UP &&
                keyCode != KeyEvent.KEYCODE_PAGE_DOWN
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> Decision(
                consume = true,
                cancelZap = cancelZap,
                command = Command.EXIT_FULLSCREEN,
                markConsumedUntilUp = true,
            )
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> Decision(true, cancelZap, Command.ZAP_UP)
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> Decision(true, cancelZap, Command.ZAP_DOWN)
            KeyEvent.KEYCODE_MENU -> Decision(true, cancelZap, Command.MENU)
            // INFO follows normal TV behaviour and controls the programme card.
            // Technical stream diagnostics remain available from MENU.
            KeyEvent.KEYCODE_INFO -> Decision(true, cancelZap, Command.TOGGLE_CAPTION)
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> Decision(true, cancelZap, Command.CATCHUP)
            // LEFT/RIGHT are intentionally consumed: there is no hidden focus target.
            else -> Decision(true, cancelZap, Command.NONE)
        }
    }

    /**
     * While the guide is open, RecyclerView owns D-pad and confirm events. Activity
     * handles only global player commands so focus can never escape to hidden panes.
     */
    private fun decideGuide(keyCode: Int, isDown: Boolean, repeatCount: Int): Decision {
        val handledKey = when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> true
            // Let the focused RecyclerView row handle ordinary vertical navigation.
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> false
            else -> false
        }
        if (!handledKey) return NOT_HANDLED
        if (!isDown || repeatCount > 0) return CONSUME_ONLY
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT -> Decision(
                consume = true,
                command = Command.CLOSE_GUIDE,
                markConsumedUntilUp = true,
            )
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> Decision(true, command = Command.GUIDE_ZAP_UP)
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> Decision(true, command = Command.GUIDE_ZAP_DOWN)
            KeyEvent.KEYCODE_MENU -> Decision(true, command = Command.GUIDE_MENU)
            // The dedicated guide already keeps programme information visible.
            KeyEvent.KEYCODE_INFO -> CONSUME_ONLY
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_PROG_RED -> Decision(true, command = Command.GUIDE_CATCHUP)
            // RIGHT is deliberately consumed so focus stays inside the guide.
            else -> CONSUME_ONLY
        }
    }
}
