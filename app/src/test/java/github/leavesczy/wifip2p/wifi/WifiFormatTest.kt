package github.leavesczy.wifip2p.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Wi-Fi report: channels, signal labels and the Wi-Fi generation, from what Android reports. */
class WifiFormatTest {

    @Test
    fun channelsOfTwoPointFourAndFiveGhz() {
        assertEquals(1, WifiFormat.channelOf(2412))
        assertEquals(6, WifiFormat.channelOf(2437))
        assertEquals(13, WifiFormat.channelOf(2472))
        assertEquals(14, WifiFormat.channelOf(2484))
        assertEquals(36, WifiFormat.channelOf(5180))
        assertEquals(149, WifiFormat.channelOf(5745))
        assertEquals(165, WifiFormat.channelOf(5825))
        assertNull(WifiFormat.channelOf(2450))
        assertNull(WifiFormat.channelOf(-1))
    }

    @Test
    fun signalLabelsFollowTheDbm() {
        assertEquals(SignalLevel.EXCELLENT, WifiFormat.signalLevel(-50))
        assertEquals(SignalLevel.GOOD, WifiFormat.signalLevel(-60))
        assertEquals(SignalLevel.FAIR, WifiFormat.signalLevel(-70))
        assertEquals(SignalLevel.WEAK, WifiFormat.signalLevel(-85))
        assertEquals(SignalLevel.UNKNOWN, WifiFormat.signalLevel(WifiFormat.UNKNOWN_RSSI))
        assertEquals(SignalLevel.UNKNOWN, WifiFormat.signalLevel(5))
    }

    @Test
    fun wifiGenerationOnlyForTheKnownStandards() {
        assertEquals(4, WifiFormat.generation(4))
        assertEquals(5, WifiFormat.generation(5))
        assertEquals(6, WifiFormat.generation(6))
        assertEquals(7, WifiFormat.generation(7))
        assertNull(WifiFormat.generation(1))
        assertNull(WifiFormat.generation(0))
    }
}
