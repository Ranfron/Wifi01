package github.leavesczy.wifip2p.ui

import android.content.Context
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.band.Band
import github.leavesczy.wifip2p.wifi.SignalLevel
import github.leavesczy.wifip2p.wifi.WifiFormat
import github.leavesczy.wifip2p.wifi.WifiSnapshot

/** The Wi-Fi report as text, in the language the user chose. Values Android does not give are left out. */
object WifiInfoText {

    fun render(context: Context, s: WifiSnapshot): String {
        val lines = mutableListOf<String>()
        val unknown = context.getString(R.string.wifi_value_unknown)
        when {
            !s.wifiEnabled -> lines += context.getString(R.string.wifi_info_off)
            !s.connected -> lines += context.getString(R.string.wifi_info_not_connected)
            else -> {
                val name = s.networkName
                lines += if (name != null) {
                    context.getString(R.string.wifi_info_network, name)
                } else {
                    context.getString(R.string.wifi_info_network_hidden)
                }
                val band = Band.ofFrequency(s.frequencyMhz)
                if (band != null) {
                    val bandText = context.getString(
                        if (band == Band.FIVE_GHZ) R.string.wifi_band_five else R.string.wifi_band_two
                    )
                    val channel = WifiFormat.channelOf(s.frequencyMhz)
                    lines += if (channel != null) {
                        context.getString(R.string.wifi_info_band, bandText, channel)
                    } else {
                        context.getString(R.string.wifi_info_band_only, bandText)
                    }
                }
                if (s.frequencyMhz > 0) {
                    lines += context.getString(R.string.wifi_info_frequency, s.frequencyMhz)
                }
                val level = when (WifiFormat.signalLevel(s.rssiDbm)) {
                    SignalLevel.EXCELLENT -> R.string.wifi_signal_excellent
                    SignalLevel.GOOD -> R.string.wifi_signal_good
                    SignalLevel.FAIR -> R.string.wifi_signal_fair
                    SignalLevel.WEAK -> R.string.wifi_signal_weak
                    SignalLevel.UNKNOWN -> R.string.wifi_value_unknown
                }
                if (WifiFormat.signalLevel(s.rssiDbm) != SignalLevel.UNKNOWN) {
                    lines += context.getString(R.string.wifi_info_signal, context.getString(level), s.rssiDbm)
                }
                if (s.linkSpeedMbps > 0) {
                    lines += context.getString(R.string.wifi_info_speed, s.linkSpeedMbps)
                }
                WifiFormat.generation(s.wifiStandard)?.let {
                    lines += context.getString(R.string.wifi_info_standard, it)
                }
                lines += context.getString(R.string.wifi_info_ip, s.ipv4 ?: unknown)
            }
        }
        lines += context.getString(
            R.string.wifi_info_five_support,
            context.getString(if (s.fiveGhzSupported) R.string.wifi_yes else R.string.wifi_no)
        )
        lines += context.getString(
            R.string.wifi_info_direct,
            context.getString(if (s.wifiDirectSupported) R.string.wifi_supported else R.string.wifi_not_supported)
        )
        lines += context.getString(R.string.wifi_info_note)
        return lines.joinToString("\n")
    }
}
