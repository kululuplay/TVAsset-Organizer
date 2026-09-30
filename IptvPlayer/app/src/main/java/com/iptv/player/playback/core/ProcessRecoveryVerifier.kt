package com.iptv.player.playback.core

/**
 * Decides, from evidence that exists on production devices, whether the
 * recovery process may kill the main process it was asked to replace and
 * whether that process is gone afterwards.
 *
 * Release (non-debuggable) app processes are not dumpable and Android mounts
 * /proc with hidepid=2, so a sibling process of the same app cannot see
 * /proc/<pid> of the main process at all. The previous check read that as
 * "process already gone", never killed anything and relaunched the screen into
 * the same poisoned process; customers then stayed on "Wird geladen…" until a
 * power cycle. The main process now hands over a Binder token whose death the
 * kernel reports to the recovery process: proof that works everywhere.
 */
internal object ProcessRecoveryVerifier {

    enum class State { VERIFIED, GONE, INVALID }

    /** Result of kill(pid, 0). */
    enum class SignalProbe { EXISTS, NO_SUCH_PROCESS, NOT_OURS, UNAVAILABLE }

    data class Evidence(
        val pid: Int,
        val ownPid: Int,
        /** Token owner alive; null when the request carried no token. */
        val tokenAlive: Boolean?,
        val signalProbe: SignalProbe,
        val expectedStartTicks: Long,
        /** From /proc/<pid>/stat; null when unreadable (release builds). */
        val observedStartTicks: Long?,
        /** As listed by the ActivityManager; null when the pid is not listed. */
        val managerProcessName: String?,
        val managerUid: Int?,
        /** From /proc/<pid>/cmdline and status; null when unreadable. */
        val procCommandLine: String?,
        val procUid: Int?,
        val packageName: String,
        val ownUid: Int,
    )

    fun decide(e: Evidence): State {
        if (e.pid <= 0 || e.pid == e.ownPid) return State.INVALID
        // Gone when the kernel says so: the token owner died, or the pid no
        // longer exists (or, without a token, belongs to another app now).
        if (e.tokenAlive == false) return State.GONE
        if (e.signalProbe == SignalProbe.NO_SUCH_PROCESS) return State.GONE
        if (e.tokenAlive == null && e.signalProbe == SignalProbe.NOT_OURS) return State.GONE
        // Everything readable must agree; a contradiction means the pid was
        // reused by another process of this app.
        val managerVerified = e.managerProcessName?.let { name ->
            name == e.packageName && e.managerUid == e.ownUid
        }
        val procVerified = e.procCommandLine?.let { commandLine ->
            commandLine == e.packageName && e.procUid == e.ownUid
        }
        if (managerVerified == false || procVerified == false) return State.INVALID
        if (
            e.observedStartTicks != null &&
            e.expectedStartTicks > 0L &&
            e.observedStartTicks != e.expectedStartTicks
        ) {
            return State.INVALID
        }
        val verified = e.tokenAlive == true || managerVerified == true || procVerified == true
        return if (verified) State.VERIFIED else State.INVALID
    }
}
