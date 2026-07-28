package com.iptv.player.ui.home

/**
 * Pure decision model for the two-step TV interaction:
 * first OK starts preview, second OK enters fullscreen only after playback is ready.
 */
internal object LivePreviewPressPolicy {

    enum class Phase { IDLE, STARTING, READY, FAILED }

    enum class Action { START_PREVIEW, QUEUE_FULLSCREEN, ENTER_FULLSCREEN }

    fun decide(sameChannel: Boolean, phase: Phase): Action =
        when {
            !sameChannel -> Action.START_PREVIEW
            phase == Phase.STARTING -> Action.QUEUE_FULLSCREEN
            phase == Phase.READY -> Action.ENTER_FULLSCREEN
            else -> Action.START_PREVIEW
        }
}
