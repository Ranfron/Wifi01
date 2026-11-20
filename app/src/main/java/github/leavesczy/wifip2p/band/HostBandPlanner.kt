package github.leavesczy.wifip2p.band

/** Why the group is on the band it is on. The host sees this, so nothing changes silently. */
enum class BandNote { NONE, HOST_NO_FIVE, FIVE_FAILED, LOW_BAND_BY_HOST }

/** Short texts for the host while a group is being built. The app shows them in the user's language. */
enum class HostStatus { CREATING, REMOVING, FIVE_FAILED_USING_TWO, FAILED }

sealed interface HostPhase {
    object Idle : HostPhase
    data class Creating(val epoch: Int, val target: Band, val note: BandNote) : HostPhase
    data class Removing(val epoch: Int, val target: Band, val note: BandNote) : HostPhase
    data class Ready(val epoch: Int, val band: Band, val nonce: String, val note: BandNote) : HostPhase
    data class Failed(val epoch: Int, val reason: Int) : HostPhase
}

sealed interface HostEffect {
    /** Build a group on [band]. The answer comes back as [HostBandPlanner.onGroupCreated] with the same [epoch]. */
    data class CreateGroup(val epoch: Int, val band: Band) : HostEffect

    /** Remove the current group first. The answer comes back as [HostBandPlanner.onGroupRemoved]. */
    data class RemoveGroup(val epoch: Int) : HostEffect

    /** The group is ready: show its QR code (the planner's phase has the band, the note and the new nonce). */
    object ShowQr : HostEffect

    data class ShowStatus(val status: HostStatus) : HostEffect
}

/**
 * The host's band decisions as a pure state machine. Every answer from the Wi-Fi Direct system carries the epoch of the
 * request it answers; an answer from an older request is ignored. Nothing here waits for time to pass.
 *
 *  - 5 GHz is tried first when this phone and the user's setting allow it.
 *  - If 5 GHz fails, the group is rebuilt on 2.4 GHz and the note says so (never a silent switch).
 *  - "Low band QR" removes the group and builds a 2.4 GHz one with a new nonce.
 *  - Every Ready phase has a new nonce; the old QR code stops working.
 */
class HostBandPlanner(private val newNonce: () -> String) {

    var phase: HostPhase = HostPhase.Idle
        private set

    private var epoch = 0

    /** True while a group is being built or replaced: nothing else may start a group now. */
    val busy: Boolean
        get() = phase is HostPhase.Creating || phase is HostPhase.Removing

    /** Starts (or restarts) the group. [wantFive] is the user's preference; [hostFiveGhz] is this phone's capability. */
    fun start(hostFiveGhz: Boolean, wantFive: Boolean): List<HostEffect> {
        epoch++
        return when {
            wantFive && hostFiveGhz -> begin(Band.FIVE_GHZ, BandNote.NONE)
            wantFive -> begin(Band.TWO_POINT_FOUR_GHZ, BandNote.HOST_NO_FIVE)
            else -> begin(Band.TWO_POINT_FOUR_GHZ, BandNote.NONE)
        }
    }

    /** The answer to [HostEffect.CreateGroup]. [band] is the band the system enforced, null when it could not enforce one. */
    fun onGroupCreated(answeredEpoch: Int, ok: Boolean, band: Band?, reason: Int): List<HostEffect> {
        val current = phase as? HostPhase.Creating ?: return emptyList()
        if (answeredEpoch != epoch || answeredEpoch != current.epoch) {
            return emptyList()
        }
        if (ok) {
            phase = HostPhase.Ready(epoch, band ?: Band.AUTO, newNonce(), current.note)
            return listOf(HostEffect.ShowQr)
        }
        if (current.target == Band.FIVE_GHZ) {
            phase = HostPhase.Removing(epoch, Band.TWO_POINT_FOUR_GHZ, BandNote.FIVE_FAILED)
            return listOf(HostEffect.ShowStatus(HostStatus.FIVE_FAILED_USING_TWO), HostEffect.RemoveGroup(epoch))
        }
        phase = HostPhase.Failed(epoch, reason)
        return listOf(HostEffect.ShowStatus(HostStatus.FAILED))
    }

    /** The answer to [HostEffect.RemoveGroup]. A failed removal still continues: then there was no group to remove. */
    fun onGroupRemoved(answeredEpoch: Int): List<HostEffect> {
        val current = phase as? HostPhase.Removing ?: return emptyList()
        if (answeredEpoch != epoch) {
            return emptyList()
        }
        phase = HostPhase.Creating(epoch, current.target, current.note)
        return listOf(HostEffect.ShowStatus(HostStatus.CREATING), HostEffect.CreateGroup(epoch, current.target))
    }

    /** The user pressed "Low band QR". Ignored while a group is still being built or replaced. */
    fun onLowBandRequested(): List<HostEffect> {
        if (phase !is HostPhase.Ready && phase !is HostPhase.Failed) {
            return emptyList()
        }
        epoch++
        phase = HostPhase.Removing(epoch, Band.TWO_POINT_FOUR_GHZ, BandNote.LOW_BAND_BY_HOST)
        return listOf(HostEffect.ShowStatus(HostStatus.REMOVING), HostEffect.RemoveGroup(epoch))
    }

    /** The host session ended: answers still on their way are ignored from now on. */
    fun stop() {
        epoch++
        phase = HostPhase.Idle
    }

    private fun begin(target: Band, note: BandNote): List<HostEffect> {
        phase = HostPhase.Creating(epoch, target, note)
        return listOf(HostEffect.ShowStatus(HostStatus.CREATING), HostEffect.CreateGroup(epoch, target))
    }
}
