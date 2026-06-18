package top.niunaijun.blackboxa.view.net

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R

class ConnectionAdapter(
    private val onClick: (ConnectionRecord) -> Unit
) : ListAdapter<ConnectionRecord, ConnectionAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConnectionRecord>() {
            override fun areItemsTheSame(a: ConnectionRecord, b: ConnectionRecord) = a.id == b.id
            override fun areContentsTheSame(a: ConnectionRecord, b: ConnectionRecord): Boolean {
                return a.status == b.status &&
                       a.host == b.host &&
                       a.bytesSent == b.bytesSent &&
                       a.bytesReceived == b.bytesReceived &&
                       a.responseCode == b.responseCode
            }
        }
    }

    // Active filters — set externally, then call submitFiltered()
    var filterProto: Protocol? = null      // null = show all
    var filterStatus: ConnStatus? = null   // null = show all
    var filterDirection: Direction? = null // null = show all

    private var fullList: List<ConnectionRecord> = emptyList()

    fun submitFiltered(list: List<ConnectionRecord>) {
        fullList = list
        applyFilters()
    }

    fun applyFilters() {
        var filtered = fullList
        filterProto?.let { p ->
            val targetLabel = p.label
            filtered = filtered.filter { it.displayProto == targetLabel || it.protocol == p }
        }
        filterStatus?.let { s -> filtered = filtered.filter { it.status == s } }
        filterDirection?.let { d ->
            filtered = when (d) {
                Direction.OUTBOUND -> filtered.filter { it.bytesSent > 0 }
                Direction.INBOUND  -> filtered.filter { it.bytesReceived > 0 }
                else -> filtered
            }
        }
        submitList(filtered)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvProto:      TextView = view.findViewById(R.id.tv_proto)
        val tvHost:       TextView = view.findViewById(R.id.tv_host)
        val tvPath:       TextView = view.findViewById(R.id.tv_path)
        val tvMethod:     TextView = view.findViewById(R.id.tv_method)
        val tvStatus:     TextView = view.findViewById(R.id.tv_conn_status)
        val tvBytes:      TextView = view.findViewById(R.id.tv_bytes)
        val tvPort:       TextView = view.findViewById(R.id.tv_port)
        val tvRespCode:   TextView = view.findViewById(R.id.tv_resp_code)
        val tvTime:       TextView = view.findViewById(R.id.tv_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_connection, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val rec = getItem(pos)

        // Protocol badge
        val protoLabel = rec.displayProto
        val protoColor = protoColorFor(protoLabel)
        h.tvProto.text = " $protoLabel "
        h.tvProto.setBackgroundColor(Color.parseColor(protoColor))

        // Host + port
        h.tvHost.text = rec.displayHost
        h.tvPort.text = ":${rec.dstPort}"

        // Method (HTTP) or direction arrow
        if (rec.method.isNotBlank()) {
            h.tvMethod.visibility = View.VISIBLE
            h.tvMethod.text = rec.method
        } else {
            h.tvMethod.visibility = View.GONE
        }

        // Path
        if (rec.path.isNotBlank()) {
            h.tvPath.visibility = View.VISIBLE
            h.tvPath.text = rec.displayPath.take(60)
        } else {
            h.tvPath.visibility = View.GONE
        }

        // Response code
        if (rec.responseCode > 0) {
            h.tvRespCode.visibility = View.VISIBLE
            h.tvRespCode.text = rec.responseCode.toString()
            h.tvRespCode.setTextColor(respCodeColor(rec.responseCode))
        } else {
            h.tvRespCode.visibility = View.GONE
        }

        // Status dot
        val (statusText, statusColor) = when (rec.status) {
            ConnStatus.ALIVE   -> "● LIVE" to "#69F0AE"
            ConnStatus.CLOSING -> "◐ FIN"  to "#FFD54F"
            ConnStatus.CLOSED  -> "○ DONE" to "#546E7A"
            ConnStatus.ERROR   -> "✕ ERR"  to "#EF9A9A"
        }
        h.tvStatus.text = statusText
        h.tvStatus.setTextColor(Color.parseColor(statusColor))

        // Traffic bytes
        val sb = StringBuilder()
        if (rec.bytesSent > 0) sb.append("↑ ${formatBytes(rec.bytesSent)}")
        if (rec.bytesReceived > 0) {
            if (sb.isNotEmpty()) sb.append("  ")
            sb.append("↓ ${formatBytes(rec.bytesReceived)}")
        }
        h.tvBytes.text = sb.toString()

        // Time
        val elapsed = System.currentTimeMillis() - rec.startTime
        h.tvTime.text = when {
            elapsed < 60_000  -> "${elapsed / 1000}s ago"
            elapsed < 3600000 -> "${elapsed / 60000}m ago"
            else              -> android.text.format.DateFormat.format("HH:mm:ss", rec.startTime)
        }

        h.itemView.setOnClickListener { onClick(rec) }
    }

    private fun protoColorFor(label: String): String = when (label) {
        "HTTP"  -> "#006064"
        "TLS"   -> "#BF360C"
        "WS"    -> "#F57F17"
        "WSS"   -> "#E65100"
        "DNS"   -> "#4A148C"
        "UDP"   -> "#1B5E20"
        "TCP"   -> "#0D47A1"
        else    -> "#263238"
    }

    private fun respCodeColor(code: Int): Int = when {
        code < 300 -> Color.parseColor("#69F0AE")
        code < 400 -> Color.parseColor("#FFD54F")
        code < 500 -> Color.parseColor("#FF8A65")
        else       -> Color.parseColor("#EF9A9A")
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024       -> "${b}B"
        b < 1024 * 1024 -> "%.1fKB".format(b / 1024.0)
        else            -> "%.1fMB".format(b / (1024.0 * 1024))
    }
}
