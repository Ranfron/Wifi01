package github.leavesczy.wifip2p.caps

import android.os.Build

/**
 * Picks the highest common band that both phones can actually use.
 *
 * Priority: 6 GHz → 5 GHz → 2.4 GHz → AUTO
 *
 * 6 GHz requires API 36+ on both sides and both reporting 6 GHz support.
 * We never force a band the other side cannot join.
 */
object BandSelector {

    fun select(guest: DeviceCaps, host: DeviceCaps): BandChoice {
        val common = guest.supportedBands intersect host.supportedBands

        // 6 GHz only when both are on Android 16+ (API 36) and both claim support
        if (6 in common &&
            guest.apiLevel >= 36 && host.apiLevel >= 36 &&
            guest.isP2pR2 && host.isP2pR2
        ) {
            return BandChoice.BAND_6GHZ
        }

        if (5 in common) {
            return BandChoice.BAND_5GHZ
        }

        if (2 in common) {
            return BandChoice.BAND_2_4GHZ
        }

        // Should almost never happen; still give the framework a chance
        return BandChoice.AUTO
    }

    /** Human readable label for UI / logs. */
    fun label(choice: BandChoice): String = when (choice) {
        BandChoice.BAND_6GHZ -> "6 GHz"
        BandChoice.BAND_5GHZ -> "5 GHz"
        BandChoice.BAND_2_4GHZ -> "2.4 GHz"
        BandChoice.AUTO -> "Auto"
    }

    /**
     * Who should become Group Owner.
     * Higher score (better radio + more cores + faster storage) wins.
     * GO carries beacon/management overhead → give it to the stronger device.
     */
    fun groupOwnerIntent(myCaps: DeviceCaps, peerCaps: DeviceCaps): Int {
        return if (myCaps.score >= peerCaps.score) 15 else 1
    }
}
