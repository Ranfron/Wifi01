package github.leavesczy.wifip2p.transfer

import github.leavesczy.wifip2p.band.Band
import github.leavesczy.wifip2p.band.Nonces
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The content of the QR code a hosting phone shows: everything the scanning phone needs to join the
 * Wi-Fi Direct group and to find the app on it - no device discovery, no invitation dialog.
 *
 *     wifip2p://c?v=1&s=<ssid>&p=<passphrase>&a=<host ip>&o=<port>&n=<phone name>&b=<band>&c=<host caps>&x=<nonce>
 *
 * b: 5 GHz, 2 for 2.4 GHz, a for automatic (the system chose). c: 5 when the host supports 5 GHz, - otherwise.
 * x: a token of this code; the host refuses connections that carry an older one.
 *
 * The code is kept small on purpose (a small QR code has big modules and is scanned reliably and
 * fast): the phone name is reduced to plain ASCII, the real name is exchanged after connecting.
 */
data class ConnectCode(
    val ssid: String,
    val passphrase: String,
    val address: String,
    val port: Int,
    val deviceName: String,
    val band: Band = Band.AUTO,
    val hostFiveGhz: Boolean = false,
    val nonce: String = ""
) {

    fun encode(): String {
        return PREFIX + "v=" + VERSION +
                "&s=" + enc(ssid) +
                "&p=" + enc(passphrase) +
                "&a=" + address +
                "&o=" + port +
                "&n=" + enc(asciiName(deviceName)) +
                "&b=" + band.code + "&c=" + (if (hostFiveGhz) "5" else "-") +
                (if (nonce.isNotEmpty()) "&x=" + nonce else "")
    }

    companion object {
        const val PREFIX = "wifip2p://c?"
        private const val VERSION = 1
        private const val MAX_NAME = 24

        private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

        /** Returns null for anything that is not a valid code of this app (never throws). */
        fun parse(text: String?): ConnectCode? {
            val raw = text?.trim() ?: return null
            if (!raw.startsWith(PREFIX) || raw.length > 400) {
                return null
            }
            val values = HashMap<String, String>()
            try {
                for (pair in raw.substring(PREFIX.length).split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq <= 0) {
                        return null
                    }
                    val key = pair.substring(0, eq)
                    if (values.containsKey(key)) {
                        return null
                    }
                    values[key] = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                }
            } catch (_: Exception) {
                return null
            }
            if (values["v"] != VERSION.toString()) {
                return null
            }
            val ssid = values["s"] ?: return null
            val pass = values["p"] ?: return null
            val address = values["a"] ?: return null
            val port = values["o"]?.toIntOrNull() ?: return null
            val name = asciiName(values["n"] ?: "")

            if (ssid.isEmpty() || ssid.toByteArray(Charsets.UTF_8).size > 32 || ssid.any { it.code < 0x20 || it.code == 0x7F }) {
                return null
            }
            if (pass.length !in 8..63 || pass.any { it.code !in 0x20..0x7E }) {
                return null
            }
            if (!isPrivateIpv4(address) || port !in 1..65535) {
                return null
            }
            val band = if (values.containsKey("b")) Band.fromCode(values["b"]) ?: return null else Band.AUTO
            val hostFive = values["c"]?.contains('5') == true
            val nonce = values["x"] ?: ""
            if (nonce.isNotEmpty() && !Nonces.isValid(nonce)) {
                return null
            }
            return ConnectCode(ssid, pass, address, port, name, band, hostFive, nonce)
        }

        /**
         * Only addresses of a local network are accepted: a QR code must never be able to steer the
         * app towards an arbitrary host on the internet or on the phone itself.
         */
        fun isPrivateIpv4(address: String): Boolean {
            val match = IPV4.matchEntire(address) ?: return false
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            if (parts.any { it !in 0..255 }) {
                return false
            }
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
