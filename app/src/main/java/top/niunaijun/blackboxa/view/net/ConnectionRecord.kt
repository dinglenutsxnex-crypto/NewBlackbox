package top.niunaijun.blackboxa.view.net

import java.util.concurrent.atomic.AtomicLong

data class PacketEvent(
    val timestamp: Long,
    val direction: Direction,
    val size: Int,
    val info: String
)

data class ConnectionRecord(
    val id: Long,
    val protocol: Protocol,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,
    val dstPort: Int,
    val startTime: Long = System.currentTimeMillis()
) {
    @Volatile var status: ConnStatus = ConnStatus.ALIVE
    @Volatile var host: String = dstIp
    @Volatile var path: String = ""
    @Volatile var method: String = ""
    @Volatile var responseCode: Int = 0
    @Volatile var bytesSent: Long = 0L
    @Volatile var bytesReceived: Long = 0L
    @Volatile var lastSeen: Long = startTime

    // Cap packet events at 100 to bound memory per connection
    private val _events = ArrayDeque<PacketEvent>(100)
    val events: List<PacketEvent> get() = synchronized(_events) { _events.toList() }

    fun addEvent(event: PacketEvent) {
        synchronized(_events) {
            if (_events.size >= 100) _events.removeFirst()
            _events.addLast(event)
        }
    }

    val displayHost: String get() = if (host.isNotBlank() && host != dstIp) host else dstIp
    val displayProto: String get() = when {
        method.isNotBlank() && (path.startsWith("WS:") || path.startsWith("WSS:")) ->
            if (protocol == Protocol.HTTPS) "WSS" else "WS"
        else -> protocol.label
    }
    val displayPath: String get() = path.removePrefix("WS:").removePrefix("WSS:").ifBlank { "/" }

    companion object {
        private val idGen = AtomicLong(0)
        fun nextId() = idGen.incrementAndGet()
    }
}
