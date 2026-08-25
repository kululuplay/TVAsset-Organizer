package com.iptv.player.player

/** Result of one native cleanup step. Kept Android-free for JVM regression tests. */
internal data class VlcCleanupStep(
    val attempted: Boolean,
    val failure: Throwable? = null,
) {
    val succeeded: Boolean
        get() = attempted && failure == null

    companion object {
        fun notAttempted(): VlcCleanupStep = VlcCleanupStep(attempted = false)
    }
}

/**
 * Structured proof for a VLC owner teardown.
 *
 * A listener detach or stop exception is important diagnostic evidence, but it
 * does not mean the provider socket is still open when both native release
 * boundaries completed. Conversely, an empty generic failure list must never be
 * treated as proof when another worker already claimed this owner.
 */
internal data class VlcNativeCleanupResult(
    val claimed: Boolean,
    val listenerDetach: VlcCleanupStep,
    val stop: VlcCleanupStep,
    val mediaPlayerRelease: VlcCleanupStep,
    val libVlcRelease: VlcCleanupStep,
) {
    val failures: List<Throwable>
        get() = listOfNotNull(
            listenerDetach.failure,
            stop.failure,
            mediaPlayerRelease.failure,
            libVlcRelease.failure,
        )

    /** Both release boundaries returning is definitive provider-ownership proof. */
    val ownershipDefinitivelyReleased: Boolean
        get() =
            claimed &&
                mediaPlayerRelease.succeeded &&
                libVlcRelease.succeeded

    companion object {
        fun notClaimed(): VlcNativeCleanupResult = VlcNativeCleanupResult(
            claimed = false,
            listenerDetach = VlcCleanupStep.notAttempted(),
            stop = VlcCleanupStep.notAttempted(),
            mediaPlayerRelease = VlcCleanupStep.notAttempted(),
            libVlcRelease = VlcCleanupStep.notAttempted(),
        )
    }
}

/** Executes every native release boundary even when an earlier step throws. */
internal object VlcNativeCleanup {

    fun runFull(
        detachListener: () -> Unit,
        stop: () -> Unit,
        releaseMediaPlayer: () -> Unit,
        releaseLibVlc: () -> Unit,
    ): VlcNativeCleanupResult = VlcNativeCleanupResult(
        claimed = true,
        listenerDetach = runStep(detachListener),
        stop = runStep(stop),
        mediaPlayerRelease = runStep(releaseMediaPlayer),
        libVlcRelease = runStep(releaseLibVlc),
    )

    fun runReleaseOnly(
        releaseMediaPlayer: () -> Unit,
        releaseLibVlc: () -> Unit,
    ): VlcNativeCleanupResult = VlcNativeCleanupResult(
        claimed = true,
        listenerDetach = VlcCleanupStep.notAttempted(),
        stop = VlcCleanupStep.notAttempted(),
        mediaPlayerRelease = runStep(releaseMediaPlayer),
        libVlcRelease = runStep(releaseLibVlc),
    )

    private fun runStep(action: () -> Unit): VlcCleanupStep = try {
        action()
        VlcCleanupStep(attempted = true)
    } catch (failure: Throwable) {
        VlcCleanupStep(attempted = true, failure = failure)
    }
}
