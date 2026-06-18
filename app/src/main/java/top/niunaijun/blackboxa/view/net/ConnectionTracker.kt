package top.niunaijun.blackboxa.view.net

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe connection tracker with LiveData for UI observation.
 * Caps at MAX_CONNECTIONS; oldest entries are evicted first.
 */
class ConnectionTracker {

    companion object {
        private const val MAX_CONNECTIONS = 500
        // Debounce UI updates to avoid flooding the main thread
        private const val UPDATE_DEBOUNCE_MS = 80L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Session key → record (active sessions for fast lookup by VPN service)
    private val sessionMap = ConcurrentHashMap<String, ConnectionRecord>()

    // Ordered list used for UI display (newest first)
    private val recordList = CopyOnWriteArrayList<ConnectionRecord>()

    private val _liveData = MutableLiveData<List<ConnectionRecord>>()
    val liveData: LiveData<List<ConnectionRecord>> = _liveData

    private var updatePending = false

    // ──────────────────────────────────────────────────────────────────────────
    // Session map helpers — called from VPN threads
    // ──────────────────────────────────────────────────────────────────────────

    fun getOrCreate(key: String, factory: () -> ConnectionRecord): ConnectionRecord {
        return sessionMap.getOrPut(key) {
            val record = factory()
            // Prepend to display list, evict oldest if over cap
            synchronized(recordList) {
                if (recordList.size >= MAX_CONNECTIONS) {
                    recordList.removeAt(recordList.lastIndex)
                }
                recordList.add(0, record)
            }
            scheduleUpdate()
            record
        }
    }

    fun get(key: String): ConnectionRecord? = sessionMap[key]

    fun remove(key: String) {
        sessionMap.remove(key)
        scheduleUpdate()
    }

    fun markClosed(key: String, status: ConnStatus = ConnStatus.CLOSED) {
        sessionMap[key]?.let { it.status = status }
        sessionMap.remove(key)
        scheduleUpdate()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI helpers — called from main thread
    // ──────────────────────────────────────────────────────────────────────────

    fun getAll(): List<ConnectionRecord> = recordList.toList()

    fun clear() {
        sessionMap.clear()
        recordList.clear()
        postUpdate(emptyList())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────────────────

    private fun scheduleUpdate() {
        if (updatePending) return
        updatePending = true
        mainHandler.postDelayed({
            updatePending = false
            postUpdate(recordList.toList())
        }, UPDATE_DEBOUNCE_MS)
    }

    private fun postUpdate(list: List<ConnectionRecord>) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _liveData.value = list
        } else {
            _liveData.postValue(list)
        }
    }

    fun forceRefresh() {
        postUpdate(recordList.toList())
    }
}
