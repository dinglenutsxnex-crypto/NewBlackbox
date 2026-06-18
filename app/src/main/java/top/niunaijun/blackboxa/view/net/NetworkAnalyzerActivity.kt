package top.niunaijun.blackboxa.view.net

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R

class NetworkAnalyzerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NetAnalyzer"
        private const val REQ_VPN = 9001

        fun start(context: Context) =
            context.startActivity(Intent(context, NetworkAnalyzerActivity::class.java))
    }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvVpnStatus:    TextView
    private lateinit var tvTargetApp:    TextView
    private lateinit var btnStart:       TextView
    private lateinit var rvConnections:  RecyclerView
    private lateinit var tvEmpty:        View
    private lateinit var tvConnCount:    TextView
    private lateinit var btnClearAll:    TextView

    // Filter tabs (proto)
    private lateinit var filterButtons:  List<Pair<TextView, Protocol?>>

    // Sub-filter (status / direction)
    private lateinit var btnAlive:   TextView
    private lateinit var btnClosed:  TextView
    private lateinit var btnOut:     TextView
    private lateinit var btnIn:      TextView

    // ── State ─────────────────────────────────────────────────────────────────
    private var vpnRunning   = false
    private var targetApp:   AppEntry? = null
    private val adapter      = ConnectionAdapter(::showDetail)

    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_analyzer)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Network Analyzer"
        }

        bindViews()
        setupFilterBar()
        setupRecyclerView()
        setupButtons()
        observeTracker()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnRunning) stopVpn()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        tvVpnStatus   = findViewById(R.id.tv_vpn_status)
        tvTargetApp   = findViewById(R.id.tv_target_app)
        btnStart      = findViewById(R.id.btn_vpn_start)
        rvConnections = findViewById(R.id.rv_connections)
        tvEmpty       = findViewById(R.id.tv_empty_hint)
        tvConnCount   = findViewById(R.id.tv_conn_count)
        btnClearAll   = findViewById(R.id.btn_clear_all)

        btnAlive  = findViewById(R.id.btn_filter_alive)
        btnClosed = findViewById(R.id.btn_filter_closed)
        btnOut    = findViewById(R.id.btn_filter_out)
        btnIn     = findViewById(R.id.btn_filter_in)
    }

    // ── Filter bar ────────────────────────────────────────────────────────────

    private fun setupFilterBar() {
        val btnAll   = findViewById<TextView>(R.id.filter_all)
        val btnHttp  = findViewById<TextView>(R.id.filter_http)
        val btnTls   = findViewById<TextView>(R.id.filter_tls)
        val btnWs    = findViewById<TextView>(R.id.filter_ws)
        val btnTcp   = findViewById<TextView>(R.id.filter_tcp)
        val btnUdp   = findViewById<TextView>(R.id.filter_udp)
        val btnDns   = findViewById<TextView>(R.id.filter_dns)

        filterButtons = listOf(
            btnAll   to null,
            btnHttp  to Protocol.HTTP,
            btnTls   to Protocol.HTTPS,
            btnWs    to Protocol.WS,
            btnTcp   to Protocol.TCP,
            btnUdp   to Protocol.UDP,
            btnDns   to Protocol.DNS
        )

        filterButtons.forEach { (btn, proto) ->
            btn.setOnClickListener {
                adapter.filterProto = proto
                highlightProtoFilter(btn)
                adapter.applyFilters()
            }
        }
        // Default = ALL selected
        highlightProtoFilter(btnAll)

        // Sub-filters
        setupToggle(btnAlive, btnClosed) { active ->
            adapter.filterStatus = if (active == btnAlive) ConnStatus.ALIVE
                                   else if (active == btnClosed) ConnStatus.CLOSED
                                   else null
            adapter.applyFilters()
        }
        setupToggle(btnOut, btnIn) { active ->
            adapter.filterDirection = if (active == btnOut) Direction.OUTBOUND
                                      else if (active == btnIn) Direction.INBOUND
                                      else null
            adapter.applyFilters()
        }
    }

    private fun highlightProtoFilter(selected: TextView) {
        filterButtons.forEach { (btn, _) ->
            btn.setTextColor(if (btn === selected) 0xFF80DEEA.toInt() else 0x80546E7A.toInt())
            btn.setBackgroundResource(
                if (btn === selected) R.drawable.bg_filter_active else android.R.color.transparent
            )
        }
    }

    /** Two-way toggle: tap active button to deselect (null), otherwise select it. */
    private fun setupToggle(btnA: TextView, btnB: TextView, onChanged: (TextView?) -> Unit) {
        var active: TextView? = null
        val toggle = { tapped: TextView ->
            active = if (active === tapped) null else tapped
            btnA.setTextColor(if (active === btnA) 0xFF80DEEA.toInt() else 0x80546E7A.toInt())
            btnB.setTextColor(if (active === btnB) 0xFF80DEEA.toInt() else 0x80546E7A.toInt())
            onChanged(active)
        }
        btnA.setOnClickListener { toggle(btnA) }
        btnB.setOnClickListener { toggle(btnB) }
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        val lm = LinearLayoutManager(this)
        lm.reverseLayout = false
        rvConnections.layoutManager = lm
        rvConnections.adapter = adapter
        rvConnections.setHasFixedSize(false)
        rvConnections.itemAnimator = null   // disable default animations for live updates
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnStart.setOnClickListener {
            if (vpnRunning) stopVpn() else requestVpnPermission()
        }

        tvTargetApp.setOnClickListener { showAppPicker() }

        btnClearAll.setOnClickListener {
            NetworkAnalyzerVpnService.tracker.clear()
            adapter.submitFiltered(emptyList())
            updateEmptyState(true)
        }
    }

    // ── VPN lifecycle ─────────────────────────────────────────────────────────

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            // Already have permission
            startVpn()
        } else {
            startActivityForResult(intent, REQ_VPN)
        }
    }

    @Deprecated("still using onActivityResult for VPN intent — standard pattern")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            startVpn()
        } else if (requestCode == REQ_VPN) {
            Toast.makeText(this, "VPN permission denied — cannot start capture", Toast.LENGTH_LONG).show()
        }
    }

    private fun startVpn() {
        try {
            vpnRunning = true
            val svc = Intent(this, NetworkAnalyzerVpnService::class.java).apply {
                action = NetworkAnalyzerVpnService.ACTION_START
                targetApp?.let { putExtra(NetworkAnalyzerVpnService.EXTRA_PACKAGE, it.packageName) }
            }
            startService(svc)
            updateVpnUi(running = true)
            Log.i(TAG, "VPN service started. Target: ${targetApp?.label ?: "ALL"}")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn error: ${e.message}")
            Toast.makeText(this, "Failed to start VPN: ${e.message}", Toast.LENGTH_LONG).show()
            vpnRunning = false
        }
    }

    private fun stopVpn() {
        vpnRunning = false
        try {
            val svc = Intent(this, NetworkAnalyzerVpnService::class.java).apply {
                action = NetworkAnalyzerVpnService.ACTION_STOP
            }
            startService(svc)
        } catch (e: Exception) {
            Log.e(TAG, "stopVpn error: ${e.message}")
        }
        updateVpnUi(running = false)
    }

    private fun updateVpnUi(running: Boolean) {
        if (running) {
            tvVpnStatus.text     = "● VPN ACTIVE"
            tvVpnStatus.setTextColor(0xFF69F0AE.toInt())
            btnStart.text        = "◼ STOP"
            btnStart.setTextColor(0xFFEF9A9A.toInt())
            tvTargetApp.isEnabled = false
        } else {
            tvVpnStatus.text     = "○ VPN INACTIVE"
            tvVpnStatus.setTextColor(0xFF546E7A.toInt())
            btnStart.text        = "▶ START"
            btnStart.setTextColor(0xFF80DEEA.toInt())
            tvTargetApp.isEnabled = true
        }
    }

    // ── App Picker ────────────────────────────────────────────────────────────

    private fun showAppPicker() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_BlackBox)
            .setTitle("Select App to Monitor")
            .setView(R.layout.dialog_app_picker)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        val rv = dialog.findViewById<RecyclerView>(R.id.rv_app_list) ?: return
        val pickerAdapter = AppPickerAdapter { chosen ->
            targetApp = chosen
            tvTargetApp.text = chosen?.label ?: "All Traffic"
            dialog.dismiss()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = pickerAdapter

        // Load apps off main thread
        Thread {
            val apps = AppPickerAdapter.loadInstalledApps(packageManager)
            runOnUiThread { pickerAdapter.setApps(apps) }
        }.also { it.isDaemon = true }.start()
    }

    // ── LiveData observer ─────────────────────────────────────────────────────

    private fun observeTracker() {
        NetworkAnalyzerVpnService.tracker.liveData.observe(this) { list ->
            adapter.submitFiltered(list)
            val shown = adapter.itemCount
            tvConnCount.text = "${list.size} connections"
            updateEmptyState(shown == 0)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        tvEmpty.visibility        = if (isEmpty) View.VISIBLE else View.GONE
        rvConnections.visibility  = if (isEmpty) View.GONE else View.VISIBLE
    }

    // ── Detail dialog ─────────────────────────────────────────────────────────

    private fun showDetail(rec: ConnectionRecord) {
        val sb = StringBuilder()
        sb.appendLine("Protocol:  ${rec.displayProto}")
        sb.appendLine("Host:      ${rec.displayHost}")
        if (rec.path.isNotBlank()) sb.appendLine("Path:      ${rec.displayPath}")
        if (rec.method.isNotBlank()) sb.appendLine("Method:    ${rec.method}")
        if (rec.responseCode > 0) sb.appendLine("Response:  ${rec.responseCode}")
        sb.appendLine()
        sb.appendLine("Src:       ${rec.srcIp}:${rec.srcPort}")
        sb.appendLine("Dst:       ${rec.dstIp}:${rec.dstPort}")
        sb.appendLine()
        sb.appendLine("Sent:      ${formatBytes(rec.bytesSent)}")
        sb.appendLine("Received:  ${formatBytes(rec.bytesReceived)}")
        sb.appendLine("Status:    ${rec.status}")
        sb.appendLine("Started:   ${DateFormat.format("HH:mm:ss.SSS", rec.startTime)}")
        sb.appendLine("Last seen: ${DateFormat.format("HH:mm:ss.SSS", rec.lastSeen)}")

        val events = rec.events
        if (events.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("─── Packet events (${events.size}) ───")
            events.takeLast(20).forEach { ev ->
                val ts = DateFormat.format("HH:mm:ss.SSS", ev.timestamp)
                val dir = if (ev.direction == Direction.OUTBOUND) "→" else "←"
                sb.appendLine("$ts  $dir  ${ev.info}")
            }
        }

        AlertDialog.Builder(this, R.style.Theme_BlackBox)
            .setTitle("${rec.displayProto}  ${rec.displayHost}:${rec.dstPort}")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatBytes(b: Long): String = when {
        b == 0L         -> "—"
        b < 1024        -> "${b} B"
        b < 1_048_576   -> "%.1f KB".format(b / 1024.0)
        else            -> "%.2f MB".format(b / 1_048_576.0)
    }
}
