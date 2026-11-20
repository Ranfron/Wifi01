package github.leavesczy.wifip2p.transport

/**
 * Abstraction over the data path.
 * Current production path is TcpTransport (existing DuplexLink + sessions).
 * Future: SmuffTransport (native UDP) will implement the same interface.
 */
interface Transport {
    fun stats(): TransportStats
    fun close()
}

data class TransportStats(
    val currentRateMBps: Float = 0f,
    val rttMs: Float = 0f,
    val packetLossPct: Float = 0f,
    val retransmits: Long = 0,
    val name: String = "TCP"
)

/**
 * Runtime choice. For now always returns the TCP path indicator.
 * When native SMUFF is ready this will probe Netlink / SO_MEMINFO and decide.
 */
object TransportFactory {
    fun preferredName(
        apiLevel: Int,
        linkSpeedMbps: Int,
        nativeAvailable: Boolean = false
    ): String {
        // Conservative: require high link speed + native ready before claiming SMUFF
        return if (nativeAvailable && apiLevel >= 29 && linkSpeedMbps > 400) {
            "SMUFF"
        } else {
            "TCP"
        }
    }
}
