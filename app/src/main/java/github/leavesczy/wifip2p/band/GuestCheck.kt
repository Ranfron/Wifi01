package github.leavesczy.wifip2p.band

/** What the scanning phone does before it joins: refuse if the group can never work on this phone. */
sealed interface JoinDecision {
    object Join : JoinDecision
    object RefuseNoFive : JoinDecision
}

/** What the scanning phone sees after it joined: the band of the connection, when the system tells it. */
sealed interface BandVerdict {
    object Matched : BandVerdict
    object Unknown : BandVerdict
    data class Mismatch(val connected: Band) : BandVerdict
}

/** The guest side of the band rules. No timers, no waiting: each decision is made from what is already known. */
object GuestCheck {

    /** A 5 GHz group cannot be joined by a phone that does not support 5 GHz, so there is no point trying. */
    fun beforeJoin(hostBand: Band, guestSupportsFive: Boolean): JoinDecision =
        if (hostBand == Band.FIVE_GHZ && !guestSupportsFive) JoinDecision.RefuseNoFive else JoinDecision.Join

    /** [connectedMhz] is -1 when the system does not say (then nothing is claimed either way). */
    fun afterJoin(hostBand: Band, connectedMhz: Int): BandVerdict {
        if (hostBand == Band.AUTO) {
            return BandVerdict.Unknown
        }
        val connected = Band.ofFrequency(connectedMhz) ?: return BandVerdict.Unknown
        return if (connected == hostBand) BandVerdict.Matched else BandVerdict.Mismatch(connected)
    }
}
