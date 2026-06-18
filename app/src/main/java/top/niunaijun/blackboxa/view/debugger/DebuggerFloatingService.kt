package top.niunaijun.blackboxa.view.debugger

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class DebuggerFloatingService : Service() {

    companion object {
        private const val TAG = "DebuggerFloat"

        private val LIFECYCLE_KEYWORDS = listOf(
            "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
            "onBind", "onUnbind", "onReceive", "onHandleIntent",
            "onCreateView", "onViewCreated", "onActivityCreated",
            "dispatchTouchEvent", "onClick", "onLongClick",
            "onRequestPermissionsResult", "onActivityResult",
        )
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isPanelExpanded = false

    private var selectedProcessPkg: String? = null
    private var selectedProcessPid: Int = -1
    private var filterMode = "ALL"

    private val logBuffer = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var logcatFuture: Future<*>? = null
    private var logcatProcess: Process? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var processAdapter: ProcessListAdapter? = null

    // Always keep FLAG_NOT_FOCUSABLE — removing it causes the overlay to steal
    // focus from the entire system, freezing all other touches.
    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private val params: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showFloatingView()
    }

    private fun showFloatingView() {
        try {
            val inflater = LayoutInflater.from(this)
            floatView = inflater.inflate(R.layout.view_debugger_float, null)
            setupBubble()
            setupPanel()
            windowManager.addView(floatView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing floating view: ${e.message}")
            showToast("Debugger failed to start: ${e.message}")
        }
    }

    private fun setupBubble() {
        val bubble = floatView?.findViewById<ImageView>(R.id.debugger_bubble) ?: return
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) togglePanel()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPanel() {
        // No flag manipulation here — FLAG_NOT_FOCUSABLE stays on permanently.
        // Buttons and ScrollView receive touch events just fine within the window bounds.
        val btnCollapse  = floatView?.findViewById<ImageView>(R.id.btn_collapse)        ?: return
        val btnRefresh   = floatView?.findViewById<Button>(R.id.btn_refresh_processes)  ?: return
        val btnStop      = floatView?.findViewById<Button>(R.id.btn_stop_logging)       ?: return
        val btnFilterAll = floatView?.findViewById<Button>(R.id.btn_filter_all)         ?: return
        val btnFilterErr = floatView?.findViewById<Button>(R.id.btn_filter_error)       ?: return
        val btnFilterTrc = floatView?.findViewById<Button>(R.id.btn_filter_trace)       ?: return
        val btnClear     = floatView?.findViewById<Button>(R.id.btn_clear_logs)         ?: return
        val rvProcesses  = floatView?.findViewById<RecyclerView>(R.id.rv_processes)     ?: return

        btnCollapse.setOnClickListener { collapsePanel() }

        processAdapter = ProcessListAdapter { processInfo -> selectProcess(processInfo) }
        rvProcesses.layoutManager = LinearLayoutManager(this)
        rvProcesses.adapter = processAdapter

        btnRefresh.setOnClickListener { loadProcesses() }
        btnStop.setOnClickListener { stopLogging() }

        btnFilterAll.setOnClickListener {
            filterMode = "ALL"
            updateFilterLabel("▶ ALL LOGS")
        }
        btnFilterErr.setOnClickListener {
            filterMode = "ERROR"
            updateFilterLabel("▶ ERRORS ONLY")
        }
        btnFilterTrc.setOnClickListener {
            filterMode = "CALLS"
            updateFilterLabel("▶ FUNCTION CALLS")
        }
        btnClear.setOnClickListener {
            logBuffer.clear()
            mainHandler.post {
                floatView?.findViewById<TextView>(R.id.tv_logs)?.text = "Log cleared."
            }
        }
    }

    private fun updateFilterLabel(label: String) {
        mainHandler.post {
            floatView?.findViewById<TextView>(R.id.tv_filter_label)?.text = label
        }
    }

    private fun togglePanel() {
        if (isPanelExpanded) collapsePanel() else expandPanel()
    }

    private fun expandPanel() {
        isPanelExpanded = true
        floatView?.findViewById<ImageView>(R.id.debugger_bubble)?.visibility = View.GONE
        floatView?.findViewById<LinearLayout>(R.id.debugger_panel)?.visibility = View.VISIBLE
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
        loadProcesses()
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        floatView?.findViewById<ImageView>(R.id.debugger_bubble)?.visibility = View.VISIBLE
        floatView?.findViewById<LinearLayout>(R.id.debugger_panel)?.visibility = View.GONE
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------------------
    // Process detection
    // ---------------------------------------------------------------------------

    private fun loadProcesses() {
        executor.submit {
            try {
                val processes = getRunningVirtualProcesses()
                mainHandler.post {
                    processAdapter?.submitList(processes)
                    when {
                        processes.isEmpty() -> {
                            appendLog("[Debugger] No running virtual apps found.")
                            appendLog("[Debugger] Launch an app in the container first, then tap REFRESH.")
                        }
                        else -> appendLog("[Debugger] Found ${processes.size} running process(es). Tap to attach.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading processes: ${e.message}")
                showToast("Failed to load processes: ${e.message}")
                appendLog("[Debugger] ERROR loading processes: ${e.message}")
            }
        }
    }

    private fun getRunningVirtualProcesses(): List<ProcessInfo> {
        val result = mutableListOf<ProcessInfo>()
        val hostPkg = packageName

        // --- Step 1: get slot processes from ActivityManager ---
        // On API >= 28, getRunningAppProcesses() only returns our own app's processes.
        // BlackBox virtual apps run inside slots named hostPkg:p0, hostPkg:p1, etc.
        val slotProcesses = try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.filter { it.processName?.startsWith("$hostPkg:") == true }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "ActivityManager query failed: ${e.message}")
            emptyList()
        }

        // --- Step 2: try BlackBoxCore for installed virtual packages ---
        val installedPkgs: List<String> = try {
            BlackBoxCore.get().getInstalledPackages(0, 0)
                ?.mapNotNull { it?.packageName }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "BlackBoxCore.getInstalledPackages failed: ${e.message}")
            emptyList()
        }

        // --- Step 3: find which installed packages are actually running ---
        for (pkg in installedPkgs) {
            val running = try {
                BlackBoxCore.isRunningApplication(pkg, 0)
            } catch (e: Exception) {
                false
            }
            if (!running) continue

            // Try to find a matching slot PID via /proc cmdline
            val pid = slotProcesses
                .firstOrNull { proc -> cmdlineContains(proc.pid, pkg) }
                ?.pid
                ?: slotProcesses.firstOrNull()?.pid
                ?: -1

            result.add(
                ProcessInfo(
                    name = pkg.substringAfterLast('.'),
                    packageName = pkg,
                    pid = pid,
                    processLine = if (pid > 0) "$pkg (pid=$pid)" else pkg
                )
            )
        }

        // --- Step 4: if nothing matched, fall back to raw slot processes ---
        if (result.isEmpty() && slotProcesses.isNotEmpty()) {
            for (proc in slotProcesses) {
                val slot = proc.processName?.substringAfterLast(':') ?: "?"
                val cmdPkg = readCmdlinePackage(proc.pid)
                result.add(
                    ProcessInfo(
                        name = if (cmdPkg != null) cmdPkg.substringAfterLast('.') else "Slot $slot",
                        packageName = cmdPkg ?: proc.processName ?: hostPkg,
                        pid = proc.pid,
                        processLine = proc.processName ?: ""
                    )
                )
            }
        }

        return result.sortedByDescending { it.pid }
    }

    /** Check if /proc/[pid]/cmdline mentions a package name. */
    private fun cmdlineContains(pid: Int, pkg: String): Boolean {
        return try {
            File("/proc/$pid/cmdline").readText()
                .replace('\u0000', ' ')
                .trim()
                .contains(pkg, ignoreCase = true)
        } catch (_: Exception) { false }
    }

    /** Read the first word of /proc/[pid]/cmdline as the package name. */
    private fun readCmdlinePackage(pid: Int): String? {
        return try {
            val cmdline = File("/proc/$pid/cmdline")
                .readText()
                .replace('\u0000', ' ')
                .trim()
                .split(" ")
                .firstOrNull()
                ?.trim()
            if (!cmdline.isNullOrEmpty() && cmdline.contains('.') && !cmdline.startsWith("/"))
                cmdline
            else null
        } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------------------------
    // Attach / detach
    // ---------------------------------------------------------------------------

    private fun selectProcess(processInfo: ProcessInfo) {
        selectedProcessPkg = processInfo.packageName
        selectedProcessPid = processInfo.pid

        mainHandler.post {
            floatView?.findViewById<LinearLayout>(R.id.section_process_select)?.visibility = View.GONE
            floatView?.findViewById<LinearLayout>(R.id.section_logging)?.visibility = View.VISIBLE
            floatView?.findViewById<TextView>(R.id.tv_selected_process)?.text =
                "📦 ${processInfo.packageName}  PID:${if (processInfo.pid > 0) processInfo.pid else "?"}"

            logBuffer.clear()
            logBuffer.append("[Debugger] ━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            logBuffer.append("[Debugger] Attached: ${processInfo.packageName}\n")
            if (processInfo.pid > 0) logBuffer.append("[Debugger] PID: ${processInfo.pid}\n")
            logBuffer.append("[Debugger] ━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            updateLogView()
        }

        startLogcatCapture(processInfo.pid, processInfo.packageName)
    }

    private fun stopLogging() {
        stopLogcatCapture()
        selectedProcessPkg = null
        selectedProcessPid = -1

        mainHandler.post {
            floatView?.findViewById<LinearLayout>(R.id.section_process_select)?.visibility = View.VISIBLE
            floatView?.findViewById<LinearLayout>(R.id.section_logging)?.visibility = View.GONE
            appendLog("[Debugger] Detached. Select a process.")
        }
        loadProcesses()
    }

    // ---------------------------------------------------------------------------
    // Logcat capture
    // ---------------------------------------------------------------------------

    private fun startLogcatCapture(pid: Int, pkg: String) {
        stopLogcatCapture()
        logcatFuture = executor.submit {
            try {
                // Use PID filter if we have one; otherwise fall back to tag/text grep
                val cmd = when {
                    pid > 0 -> arrayOf("logcat", "-v", "threadtime", "--pid=$pid")
                    else    -> arrayOf("logcat", "-v", "threadtime")
                }

                logcatProcess = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
                var line: String?

                appendLog("[Debugger] Stream started${if (pid > 0) " (PID $pid)" else " (no PID — showing all)"}\n")
                appendLog("[Debugger] Tap CALLS for function-call-only view\n")

                while (reader.readLine().also { line = it } != null) {
                    if (Thread.currentThread().isInterrupted) break
                    val logLine = line ?: continue
                    // When no PID, filter by package name appearing in the line
                    if (pid <= 0 && !logLine.contains(pkg, ignoreCase = true)) continue
                    if (shouldShowLog(logLine)) {
                        appendLog(formatLogLine(logLine))
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "Logcat error: ${e.message}")
                    appendLog("[Debugger] Stream ended: ${e.message}")
                    showToast("Logcat error: ${e.message}")
                }
            }
        }
    }

    private fun stopLogcatCapture() {
        try {
            logcatFuture?.cancel(true)
            logcatProcess?.destroy()
            logcatProcess = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping logcat: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------
    // Log formatting / filtering
    // ---------------------------------------------------------------------------

    private fun shouldShowLog(line: String): Boolean {
        return when (filterMode) {
            "ERROR" -> line.contains(" E ") || line.contains(" E/") ||
                       line.contains("Exception") || line.contains("FATAL") ||
                       line.contains("Error") || line.contains("crash")
            "CALLS" -> isMethodCallLine(line)
            else    -> true
        }
    }

    private fun isMethodCallLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.contains("\tat ") || line.trimStart().startsWith("at ")) return true
        for (kw in LIFECYCLE_KEYWORDS) if (line.contains(kw)) return true
        return false
    }

    private fun formatLogLine(raw: String): String {
        if (filterMode == "CALLS") return formatCallLine(raw)
        val prefixed = when {
            raw.contains(" E ") || raw.contains(" E/") -> "❌ $raw"
            raw.contains(" W ") || raw.contains(" W/") -> "⚠️ $raw"
            raw.contains(" D ") || raw.contains(" D/") -> "🔵 $raw"
            raw.contains(" I ") || raw.contains(" I/") -> "ℹ️ $raw"
            raw.trimStart().startsWith("at ") || raw.contains("Exception") -> "💥 $raw"
            else -> raw
        }
        return prefixed + "\n"
    }

    private fun formatCallLine(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("at ") -> {
                val method = trimmed.removePrefix("at ").substringBefore("(")
                val cls = method.substringBeforeLast(".")
                val fn  = method.substringAfterLast(".")
                "  📍 $fn  [$cls]\n"
            }
            LIFECYCLE_KEYWORDS.any { raw.contains(it) } -> {
                val kw = LIFECYCLE_KEYWORDS.first { raw.contains(it) }
                "🔄 lifecycle → $kw  |  $raw\n"
            }
            else -> "⚡ $raw\n"
        }
    }

    private fun appendLog(text: String) {
        val timestamp = timeFormat.format(Date())
        val entry = if (text.startsWith("[Debugger]")) "$text\n" else "[$timestamp] $text"
        logBuffer.append(entry)

        if (logBuffer.length > 100_000) {
            val trimmed = logBuffer.toString().takeLast(70_000)
            logBuffer.clear()
            logBuffer.append("...[older logs trimmed]...\n")
            logBuffer.append(trimmed)
        }

        mainHandler.post { updateLogView() }
    }

    private fun updateLogView() {
        val tvLogs    = floatView?.findViewById<TextView>(R.id.tv_logs)        ?: return
        val scrollView = floatView?.findViewById<ScrollView>(R.id.scroll_logs) ?: return
        tvLogs.text = logBuffer.toString()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        stopLogcatCapture()
        executor.shutdownNow()
        try { floatView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
    }
}
