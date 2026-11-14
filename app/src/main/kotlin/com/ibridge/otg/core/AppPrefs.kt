package com.ibridge.otg.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPrefs(context: Context) {

    private val sp = context.getSharedPreferences("ibridge_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(sp.getInt(KEY_THEME_MODE, 0))
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    private val _accentIndex = MutableStateFlow(sp.getInt(KEY_ACCENT, 0))
    val accentIndex: StateFlow<Int> = _accentIndex.asStateFlow()

    private val _autoStartDaemon = MutableStateFlow(sp.getBoolean(KEY_AUTO_START, true))
    val autoStartDaemon: StateFlow<Boolean> = _autoStartDaemon.asStateFlow()

    private val _stopDaemonOnExit = MutableStateFlow(sp.getBoolean(KEY_STOP_ON_EXIT, false))
    val stopDaemonOnExit: StateFlow<Boolean> = _stopDaemonOnExit.asStateFlow()

    var daemonPort: Int
        get() = sp.getInt(KEY_DAEMON_PORT, DEFAULT_PORT)
        set(value) {
            sp.edit().putInt(KEY_DAEMON_PORT, value).apply()
        }

    var lastUdId: String
        get() = sp.getString(KEY_LAST_UDID, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_LAST_UDID, value).apply()
        }

    fun setThemeMode(mode: Int) {
        sp.edit().putInt(KEY_THEME_MODE, mode).apply()
        _themeMode.value = mode
    }

    fun setAccentIndex(index: Int) {
        sp.edit().putInt(KEY_ACCENT, index).apply()
        _accentIndex.value = index
    }

    fun setAutoStartDaemon(enabled: Boolean) {
        sp.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        _autoStartDaemon.value = enabled
    }

    fun setStopDaemonOnExit(enabled: Boolean) {
        sp.edit().putBoolean(KEY_STOP_ON_EXIT, enabled).apply()
        _stopDaemonOnExit.value = enabled
    }

    companion object {
        const val DEFAULT_PORT = 27015
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT = "accent_index"
        private const val KEY_AUTO_START = "auto_start_daemon"
        private const val KEY_STOP_ON_EXIT = "stop_daemon_on_exit"
        private const val KEY_DAEMON_PORT = "daemon_port"
        private const val KEY_LAST_UDID = "last_udid"
    }
}
