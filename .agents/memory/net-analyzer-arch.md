---
name: Network Analyzer architecture
description: VPN-based packet capture service design for the Network Analyzer feature
---

Package: top.niunaijun.blackboxa.view.net

VPN approach: Android VpnService opens a TUN fd (TUN_IP=10.99.0.1/24, route 0.0.0.0/0).
TCP relay via TcpSession (protected java.net.Socket). 
UDP relay via UdpSession (protected java.net.DatagramSocket).

**Critical ordering rule:**
Call VpnService.protect(socket) BEFORE the socket connects/sends anything.
For TcpSession: protect(session.socket) → handlePacket() → socket.connect() happens inside.
For UdpSession: protect(session.socket) → session.start() (starts receive relay thread).
UdpSession.start() is separate from constructor for this reason — do NOT call relay.submit() in init{}.

Socket visibility: TcpSession.socket and UdpSession.socket are `internal val` (not private)
so NetworkAnalyzerVpnService in the same package can call protect() on them.

Singleton tracker: NetworkAnalyzerVpnService.tracker (companion object ConnectionTracker).
NetworkAnalyzerActivity observes tracker.liveData directly. Clear on VPN start.

TLS SNI extraction: parse ClientHello extensions (type 0x0000) from first bytes of TCP payload.
HTTP inspection: parse first 4096B of TCP payload for method/host/path.
WebSocket: detected via HTTP Upgrade header — path prefixed with "WS:" in ConnectionRecord.

Session key format: "$proto|$srcIp:$srcPort→$dstIp:$dstPort"
Ring buffer caps: 500 max connections (evict oldest), 100 packet events per connection.

**Why:** Full user-space TCP relay without a library (no tun2socks/lwIP dependency).
Simple seq/ack tracking works for the common case; edge cases (TCP options, OOO packets) silently degrade.
