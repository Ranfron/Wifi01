package github.leavesczy.wifip2p.transfer

import github.leavesczy.wifip2p.band.Band
import github.leavesczy.wifip2p.band.BandNote
import github.leavesczy.wifip2p.band.BandVerdict
import github.leavesczy.wifip2p.band.GuestCheck
import github.leavesczy.wifip2p.band.HostBandPlanner
import github.leavesczy.wifip2p.band.HostEffect
import github.leavesczy.wifip2p.band.HostPhase
import github.leavesczy.wifip2p.band.HostStatus
import github.leavesczy.wifip2p.band.JoinDecision
import github.leavesczy.wifip2p.band.Nonces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The band rules: the guest's pre-join check, and the host's state machine (5 GHz first, explicit 2.4 GHz, nonces). */
class BandTest {

    private var nonceCounter = 0
    private fun planner() = HostBandPlanner { "nonce${++nonceCounter}aa" }

    private fun CreateEpoch(effects: List<HostEffect>): Int =
        effects.filterIsInstance<HostEffect.CreateGroup>().single().epoch

    @Test
    fun aFrequencyIsOnTheBandItBelongsTo() {
        assertEquals(Band.TWO_POINT_FOUR_GHZ, Band.ofFrequency(2412))
        assertEquals(Band.TWO_POINT_FOUR_GHZ, Band.ofFrequency(2484))
        assertEquals(Band.FIVE_GHZ, Band.ofFrequency(5180))
        assertEquals(Band.FIVE_GHZ, Band.ofFrequency(5745))
        assertEquals(null, Band.ofFrequency(1000))
        assertEquals(null, Band.ofFrequency(-1))
    }

    @Test
    fun aGuestWithoutFiveGhzDoesNotTryAFiveGhzGroup() {
        assertEquals(JoinDecision.RefuseNoFive, GuestCheck.beforeJoin(Band.FIVE_GHZ, guestSupportsFive = false))
        assertEquals(JoinDecision.Join, GuestCheck.beforeJoin(Band.FIVE_GHZ, guestSupportsFive = true))
        assertEquals(JoinDecision.Join, GuestCheck.beforeJoin(Band.TWO_POINT_FOUR_GHZ, guestSupportsFive = false))
        assertEquals(JoinDecision.Join, GuestCheck.beforeJoin(Band.AUTO, guestSupportsFive = false))
    }

    @Test
    fun theConnectedBandIsCheckedWhenTheSystemTellsIt() {
        assertEquals(BandVerdict.Matched, GuestCheck.afterJoin(Band.FIVE_GHZ, 5180))
        assertEquals(BandVerdict.Mismatch(Band.TWO_POINT_FOUR_GHZ), GuestCheck.afterJoin(Band.FIVE_GHZ, 2437))
        assertEquals(BandVerdict.Unknown, GuestCheck.afterJoin(Band.FIVE_GHZ, -1))
        assertEquals(BandVerdict.Unknown, GuestCheck.afterJoin(Band.AUTO, 2437))
    }

    @Test
    fun aFiveGhzHostBuildsItsGroupOnFiveGhzFirst() {
        val p = planner()
        val effects = p.start(hostFiveGhz = true, wantFive = true)
        assertTrue(p.phase is HostPhase.Creating)
        assertEquals(Band.FIVE_GHZ, (effects.filterIsInstance<HostEffect.CreateGroup>().single()).band)
        val ready = p.onGroupCreated(CreateEpoch(effects), ok = true, band = Band.FIVE_GHZ, reason = 0)
        assertEquals(listOf<HostEffect>(HostEffect.ShowQr), ready)
        val phase = p.phase as HostPhase.Ready
        assertEquals(Band.FIVE_GHZ, phase.band)
        assertEquals(BandNote.NONE, phase.note)
    }

    @Test
    fun aFailedFiveGhzGroupIsRebuiltOnTwoPointFourWithANoteNotSilently() {
        val p = planner()
        val first = p.start(hostFiveGhz = true, wantFive = true)
        val failed = p.onGroupCreated(CreateEpoch(first), ok = false, band = null, reason = 2)
        assertTrue(failed.contains(HostEffect.ShowStatus(HostStatus.FIVE_FAILED_USING_TWO)))
        val removal = failed.filterIsInstance<HostEffect.RemoveGroup>().single()
        val rebuild = p.onGroupRemoved(removal.epoch)
        val create = rebuild.filterIsInstance<HostEffect.CreateGroup>().single()
        assertEquals(Band.TWO_POINT_FOUR_GHZ, create.band)
        p.onGroupCreated(create.epoch, ok = true, band = Band.TWO_POINT_FOUR_GHZ, reason = 0)
        val phase = p.phase as HostPhase.Ready
        assertEquals(Band.TWO_POINT_FOUR_GHZ, phase.band)
        assertEquals(BandNote.FIVE_FAILED, phase.note)
    }

    @Test
    fun aHostWithoutFiveGhzSaysWhyItUsesTwoPointFour() {
        val p = planner()
        val effects = p.start(hostFiveGhz = false, wantFive = true)
        assertEquals(Band.TWO_POINT_FOUR_GHZ, effects.filterIsInstance<HostEffect.CreateGroup>().single().band)
        p.onGroupCreated(CreateEpoch(effects), ok = true, band = Band.TWO_POINT_FOUR_GHZ, reason = 0)
        assertEquals(BandNote.HOST_NO_FIVE, (p.phase as HostPhase.Ready).note)
    }

    @Test
    fun compatibilityModeUsesTwoPointFourWithoutANote() {
        val p = planner()
        val effects = p.start(hostFiveGhz = true, wantFive = false)
        assertEquals(Band.TWO_POINT_FOUR_GHZ, effects.filterIsInstance<HostEffect.CreateGroup>().single().band)
        p.onGroupCreated(CreateEpoch(effects), ok = true, band = Band.TWO_POINT_FOUR_GHZ, reason = 0)
        assertEquals(BandNote.NONE, (p.phase as HostPhase.Ready).note)
    }

    @Test
    fun lowBandQrRemovesTheGroupAndIssuesANewNonce() {
        val p = planner()
        val first = p.start(hostFiveGhz = true, wantFive = true)
        p.onGroupCreated(CreateEpoch(first), ok = true, band = Band.FIVE_GHZ, reason = 0)
        val oldNonce = (p.phase as HostPhase.Ready).nonce

        val removal = p.onLowBandRequested().filterIsInstance<HostEffect.RemoveGroup>().single()
        assertTrue(p.busy)
        val rebuild = p.onGroupRemoved(removal.epoch).filterIsInstance<HostEffect.CreateGroup>().single()
        assertEquals(Band.TWO_POINT_FOUR_GHZ, rebuild.band)
        p.onGroupCreated(rebuild.epoch, ok = true, band = Band.TWO_POINT_FOUR_GHZ, reason = 0)

        val phase = p.phase as HostPhase.Ready
        assertEquals(Band.TWO_POINT_FOUR_GHZ, phase.band)
        assertEquals(BandNote.LOW_BAND_BY_HOST, phase.note)
        assertFalse("the old QR code must stop working", phase.nonce == oldNonce)
    }

    @Test
    fun everyReadyGroupGetsANewNonce() {
        val p = planner()
        val first = p.start(hostFiveGhz = true, wantFive = true)
        p.onGroupCreated(CreateEpoch(first), ok = true, band = Band.FIVE_GHZ, reason = 0)
        val a = (p.phase as HostPhase.Ready).nonce
        val removal = p.onLowBandRequested().filterIsInstance<HostEffect.RemoveGroup>().single()
        val rebuild = p.onGroupRemoved(removal.epoch).filterIsInstance<HostEffect.CreateGroup>().single()
        p.onGroupCreated(rebuild.epoch, ok = true, band = Band.TWO_POINT_FOUR_GHZ, reason = 0)
        val b = (p.phase as HostPhase.Ready).nonce
        assertTrue(Nonces.isValid(a) || a.startsWith("nonce"))
        assertFalse(a == b)
    }

    @Test
    fun answersToAnOlderRequestAreIgnored() {
        val p = planner()
        val first = p.start(hostFiveGhz = true, wantFive = true)
        val oldEpoch = CreateEpoch(first)
        p.start(hostFiveGhz = true, wantFive = true) // the host restarted the group
        assertTrue(p.onGroupCreated(oldEpoch, ok = true, band = Band.FIVE_GHZ, reason = 0).isEmpty())
        assertTrue(p.phase is HostPhase.Creating)
    }

    @Test
    fun lowBandIsIgnoredWhileAGroupIsBeingBuilt() {
        val p = planner()
        p.start(hostFiveGhz = true, wantFive = true)
        assertTrue(p.onLowBandRequested().isEmpty())
    }

    @Test
    fun aTwoPointFourFailureIsReportedAndDoesNotLoop() {
        val p = planner()
        val effects = p.start(hostFiveGhz = false, wantFive = true)
        val result = p.onGroupCreated(CreateEpoch(effects), ok = false, band = null, reason = 2)
        assertEquals(listOf<HostEffect>(HostEffect.ShowStatus(HostStatus.FAILED)), result)
        assertTrue(p.phase is HostPhase.Failed)
    }

    @Test
    fun aSystemThatCannotChooseABandGivesAutomatic() {
        val p = planner()
        val effects = p.start(hostFiveGhz = true, wantFive = true)
        p.onGroupCreated(CreateEpoch(effects), ok = true, band = null, reason = 0)
        assertEquals(Band.AUTO, (p.phase as HostPhase.Ready).band)
    }
}
