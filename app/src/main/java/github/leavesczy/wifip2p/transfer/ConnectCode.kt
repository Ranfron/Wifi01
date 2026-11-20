package github.leavesczy.wifip2p.transfer

import github.leavesczy.wifip2p.caps.DeviceCaps
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Content of the QR code.
 *
 * v1 (legacy): wifip2p://c?v=1&s=...&p=...&a=...&o=...&n=...
 * v2 (advanced): adds &c=<compact caps> &x=<nonce echo>
 *
 * Guest QR (QR-1) can be lighter; Host QR (QR-2) carries credentials + host caps + echoed guest nonce.
 */
data class ConnectCode(
    val ssid: String,
    val passphrase: String,
    val address: String,
    val port: Int,
    val deviceName: String,
    val caps: DeviceCaps? = null,
    val nonceEcho: String? = null,   // host echoes guest nonce for replay protection
    val version: Int = if (caps != null) 2 else 1
) {

    fun encode(): String {
        val base = PREFIX + "v=" + version +
                "&s=" + enc(ssid) +
                "&p=" + enc(passphrase) +
                "&a=" + address +
                "&o=" + port +
                "&n=" + enc(asciiName(deviceName))
        val withCaps = if (caps != null) base + "&c=" + enc(caps.toCompact()) else base
        return if (!nonceEcho.isNullOrBlank()) withCaps + "&x=" + enc(nonceEcho) else withCaps
    }

    companion object {
        const val PREFIX = "wifip2p://c?"
        private const val MAX_NAME = 24
        private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

        /** Returns null for anything that is not a valid code of this app (never throws). */
        fun parse(text: String?): ConnectCode? {
            val raw = text?.trim() ?: return null
            if (!raw.startsWith(PREFIX) || raw.length > 800) return null

            val values = HashMap<String, String>()
            try {
                for (pair in raw.substring(PREFIX.length).split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq <= 0) return null
                    val key = pair.substring(0, eq)
                    if (values.containsKey(key)) return null
                    values[key] = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                }
            } catch (_: Exception) {
                return null
            }

            val ver = values["v"]?.toIntOrNull() ?: return null
            if (ver !in 1..2) return null

            val ssid = values["s"] ?: return null
            val pass = values["p"] ?: return null
            val address = values["a"] ?: return null
            val port = values["o"]?.toIntOrNull() ?: return null
            val name = asciiName(values["n"] ?: "")
            val caps = DeviceCaps.fromCompact(values["c"])
            val nonceEcho = values["x"]

            if (ssid.isEmpty() || ssid.toByteArray(Charsets.UTF_8).size > 32 ||
                ssid.any { it.code < 0x20 || it.code == 0x7F }
            ) return null
            if (pass.length !in 8..63 || pass.any { it.code !in 0x20..0x7E }) return null
            if (!isPrivateIpv4(address) || port !in 1..65535) return null

            return ConnectCode(ssid, pass, address, port, name, caps, nonceEcho, ver)
        }

        fun isPrivateIpv4(address: String): Boolean {
            val match = IPV4.matchEntire(address) ?: return false
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            if (parts.any { it !in 0..255 }) return false
            val (a, b) = parts
            return a == 10 ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 169 && b == 254)
        }

        private fun asciiName(name: String): String =
            name.filter { it.code in 0x20..0x7E }.trim().take(MAX_NAME)

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
