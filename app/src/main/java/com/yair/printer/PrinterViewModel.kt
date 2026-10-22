package com.yair.printer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screens draw, in one value. */
data class PrinterState(
    val host: String = Store.DEFAULT_HOST,
    val discoveredName: String? = null,
    val status: Ipp.Status? = null,
    val capabilities: Escl.Capabilities? = null,
    val scanner: Escl.State? = null,
    val settings: Escl.Settings = Escl.Settings(),
    val isRefreshing: Boolean = false,
    val isScanning: Boolean = false,
    val isSearching: Boolean = false,
    val scans: List<Saver.Saved> = emptyList(),
    val message: String? = null,
)

class PrinterViewModel(private val app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val _state = MutableStateFlow(PrinterState(host = store.host))
    val state: StateFlow<PrinterState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads status, capabilities and scanner state in one pass. */
    fun refresh() {
        val host = _state.value.host
        _state.update { it.copy(isRefreshing = true, message = null) }

        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { Ipp.status(host) }
            val caps = withContext(Dispatchers.IO) { Escl.capabilities(host) }
            val scanner = withContext(Dispatchers.IO) { Escl.state(host) }

            _state.update {
                it.copy(
                    status = status,
                    capabilities = caps,
                    scanner = scanner,
                    isRefreshing = false,
                    message = if (!status.reachable && caps.error != null) {
                        "אין קשר עם המדפסת בכתובת $host"
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Only the cheap part, for the pull-to-refresh of the scan screen. */
    fun refreshScannerState() {
        val host = _state.value.host
        viewModelScope.launch {
            val scanner = withContext(Dispatchers.IO) { Escl.state(host) }
            _state.update { it.copy(scanner = scanner) }
        }
    }

    fun setHost(host: String) {
        val clean = host.trim()
        if (clean.isEmpty()) return
        store.host = clean
        _state.update { it.copy(host = clean, discoveredName = null) }
        refresh()
    }

    /** Looks the printer up by its advertised name, for when the address moved. */
    fun search() {
        _state.update { it.copy(isSearching = true, message = null) }

        viewModelScope.launch {
            val found = Discovery.find(app)

            if (found == null) {
                _state.update {
                    it.copy(isSearching = false, message = "לא נמצאה מדפסת ברשת")
                }
                return@launch
            }

            store.host = found.host
            _state.update {
                it.copy(
                    host = found.host,
                    discoveredName = found.name,
                    isSearching = false,
                    message = "נמצאה: ${found.name}",
                )
            }
            refresh()
        }
    }

    fun update(settings: Escl.Settings) = _state.update { it.copy(settings = settings) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun scan() {
        val snapshot = _state.value
        val caps = snapshot.capabilities

        if (caps == null || caps.error != null) {
            _state.update { it.copy(message = "אין קשר עם הסורק") }
            return
        }

        _state.update { it.copy(isScanning = true, message = null) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                Escl.scan(snapshot.host, snapshot.settings, caps)
            }

            outcome.fold(
                onSuccess = { pages ->
                    val saved = withContext(Dispatchers.IO) {
                        pages.mapIndexed { i, page ->
                            Saver.save(app, page, i, pages.size)
                        }
                    }

                    _state.update {
                        it.copy(
                            isScanning = false,
                            scans = saved + it.scans,
                            message = if (saved.size == 1) {
                                "נסרק ונשמר: ${saved.first().name}"
                            } else {
                                "נסרקו ${saved.size} עמודים"
                            },
                        )
                    }
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(
                            isScanning = false,
                            message = failure.message ?: "הסריקה נכשלה",
                        )
                    }
                },
            )

            refreshScannerState()
        }
    }
}
