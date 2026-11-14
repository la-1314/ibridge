package com.ibridge.otg.daemon

import android.content.Context
import com.ibridge.otg.core.AppPrefs
import com.ibridge.otg.core.LogBus
import com.ibridge.otg.core.RootShell
import com.ibridge.otg.core.RootState
import com.ibridge.otg.tools.BinaryManager
import com.ibridge.otg.tools.ToolId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

sealed interface DaemonState {
    data object Stopped : DaemonState
    data object Starting : DaemonState
    data class Running(val port: Int, val pid: Long?) : DaemonState
    data class Failed(val message: String) : DaemonState
}

/**
 * Manages the usbmuxd background process.
 *
 * usbmuxd listens on a loopback TCP port (chosen at runtime so we never fight another process for
 * 27015). All idevice* children are pointed at the same port through USBMUXD_SOCKET_ADDRESS.
 */
class UsbmuxdManager(
    private val context: Context,
    private val prefs: AppPrefs,
    private val binaries: BinaryManager,
    private val root: RootShell,
    private val logs: LogBus,
) {

    private val runDir = File(context.filesDir, "usbmuxd").apply { mkdirs() }
    private val pidFile = File(runDir, "usbmuxd.pid")
    private val logFile = File(runDir, "usbmuxd.log")

    private val mutex = Mutex()

    private val _state = MutableStateFlow<DaemonState>(DaemonState.Stopped)
    val state: StateFlow<DaemonState> = _state.asStateFlow()

    private val _port = MutableStateFlow(prefs.daemonPort)
    val port: StateFlow<Int> = _port.asStateFlow()

    suspend fun refresh() {
        val pid = readPid()
        val currentPort = prefs.daemonPort
        _port.value = currentPort
        _state.value = when {
            pid != null && isProcessAlive(pid) && isPortOpen(currentPort) ->
                DaemonState.Running(currentPort, pid)

            else -> DaemonState.Stopped
        }
    }

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = _state.value
            if (existing is DaemonState.Running && isPortOpen(existing.port)) return@withLock true

            val rootState = root.state
            if (rootState != RootState.Granted) {
                _state.value = DaemonState.Failed("需要 Root 权限才能启动 usbmuxd")
                logs.w("usbmuxd", "启动失败：未获取 Root")
                return@withLock false
            }

            val binaryPath = binaries.pathOf(ToolId.USBMUXD)
            if (binaryPath == null) {
                _state.value = DaemonState.Failed("缺少 usbmuxd 二进制")
                logs.e("usbmuxd", "缺少 usbmuxd，请导入工具包")
                return@withLock false
            }

            // Probe execution; some ROMs deny exec from the app sandbox even for root.
            var binary = binaryPath
            val probe = root.run(
                "export LD_LIBRARY_PATH=${RootShell.quote(binaries.toolDir.absolutePath)}:\$LD_LIBRARY_PATH; " +
                    "${RootShell.quote(binary)} -V",
                timeoutMs = 10_000,
            )
            if (probe.code == 126 ||
                probe.combined.contains("Permission denied", ignoreCase = true)
            ) {
                val staged = binaries.stageForExecution(root)
                if (staged != null) {
                    binary = "$staged/usbmuxd"
                    logs.w("usbmuxd", "私有目录不可执行，改用 $binary")
                }
            }

            // Reuse an alive instance from a previous session.
            val oldPid = readPid()
            if (oldPid != null && isProcessAlive(oldPid) && isPortOpen(prefs.daemonPort)) {
                _state.value = DaemonState.Running(prefs.daemonPort, oldPid)
                logs.i("usbmuxd", "复用已运行的实例 pid=$oldPid port=${prefs.daemonPort}")
                return@withLock true
            }
            if (oldPid != null && isProcessAlive(oldPid)) {
                // Stale process that never opened its port; clean it up before starting again.
                root.run("kill -9 $oldPid 2>/dev/null; true", timeoutMs = 5_000)
                pidFile.delete()
            }

            val port = pickPort()
            if (port <= 0) {
                _state.value = DaemonState.Failed("找不到可用端口")
                logs.e("usbmuxd", "27015-27099 均被占用")
                return@withLock false
            }
            prefs.daemonPort = port
            _port.value = port
            _state.value = DaemonState.Starting

            runDir.mkdirs()
            pidFile.delete()
            logFile.delete()

            val q = RootShell::quote
            val configDir = File(runDir, "config").apply { mkdirs() }
            val script = buildString {
                append("export HOME=").append(q(binaries.homeDir.absolutePath)).append("; ")
                append("export TMPDIR=").append(q(binaries.tmpDir.absolutePath)).append("; ")
                append("export IBRIDGE_LOCKDOWN_DIR=")
                append(q(binaries.lockdownDir.absolutePath)).append("; ")
                append("export LD_LIBRARY_PATH=")
                append(q(File(binary).parentFile?.absolutePath.orEmpty()))
                append(":\$LD_LIBRARY_PATH; ")
                append("mkdir -p ").append(q(runDir.absolutePath)).append(' ')
                append(q(configDir.absolutePath)).append("; ")
                append("mkdir -p /var/lib 2>/dev/null; ")
                append("[ -e /var/lib/lockdown ] || ln -s ")
                append(q(binaries.lockdownDir.absolutePath))
                append(" /var/lib/lockdown 2>/dev/null; ")
                append("rm -f ").append(q(pidFile.absolutePath)).append(' ')
                append(q(logFile.absolutePath)).append("; ")
                append("if command -v setsid >/dev/null 2>&1; then ")
                append("setsid ").append(q(binary))
                elseBranch()
                append(" else ")
                append("nohup ").append(q(binary))
                elseBranch()
                append(" fi; echo started")
            }

            logs.i("usbmuxd", "启动 usbmuxd，监听 127.0.0.1:$port")
            val launch = root.run(script, timeoutMs = 19_000)
            if (!launch.ok) {
                logs.w("usbmuxd", "启动命令返回 ${launch.code}：${launch.combined.trim()}")
            }

            var running = false
            for (attempt in 0 until 30) {
                delay(250)
                val pid = readPid()
                if (isPortOpen(port) && (pid == null || isProcessAlive(pid))) {
                    running = true
                    _state.value = DaemonState.Running(port, pid)
                    logs.i("usbmuxd", "usbmuxd 已就绪，pid=${pid ?: "?"} port=$port")
                    break
                }
            }

            if (!running) {
                val tail = readLogTail()
                if (tail.isNotBlank()) logs.chunk(com.ibridge.otg.core.LogLevel.WARN, "usbmuxd", tail)
                val message = tail.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "usbmuxd 未能在端口 $port 上启动"
                _state.value = DaemonState.Failed(message)
                logs.e("usbmuxd", "启动失败：$message")
            }
            running
        }
    }

    private fun StringBuilder.elseBranch() {
        append(" -f -v -C ").append(RootShell.quote(File(runDir, "config").absolutePath))
        append(" -S 127.0.0.1:").append(prefs.daemonPort)
        append(" -P ").append(RootShell.quote(pidFile.absolutePath))
        append(" -l ").append(RootShell.quote(logFile.absolutePath))
        append(" </dev/null >/dev/null 2>&1 &")
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pid = readPid() ?: (state.value as? DaemonState.Running)?.pid
            if (pid == null) {
                pidFile.delete()
                _state.value = DaemonState.Stopped
                logs.i("usbmuxd", "没有可停止的 usbmuxd 实例")
                return@withLock true
            }
            logs.i("usbmuxd", "停止 usbmuxd pid=$pid")
            root.run(
                "kill $pid 2>/dev/null; sleep 1; kill -9 $pid 2>/dev/null; rm -f " +
                    RootShell.quote(pidFile.absolutePath) + "; true",
                timeoutMs = 6_000,
            )
            pidFile.delete()
            _state.value = DaemonState.Stopped
            _port.value = prefs.daemonPort
            true
        }
    }

    suspend fun restart(): Boolean {
        stop()
        delay(300)
        return start()
    }

    /** Frees the configured port and chooses a new one (used by Settings). */
    fun reallocatePort(): Int {
        val port = pickPort()
        if (port > 0) {
            prefs.daemonPort = port
            _port.value = port
        }
        return port
    }

    private fun pickPort(): Int {
        val preferred = prefs.daemonPort.takeIf { it in 1024..65535 } ?: AppPrefs.DEFAULT_PORT
        if (isPortFree(preferred)) return preferred
        for (candidate in 27015..27099) {
            if (isPortFree(candidate)) return candidate
        }
        return -1
    }

    private fun readPid(): Long? = runCatching {
        pidFile.readText().trim().toLongOrNull()
    }.getOrNull()

    private fun isProcessAlive(pid: Long): Boolean =
        runCatching { File("/proc/$pid").exists() }.getOrDefault(false)

    private fun readLogTail(): String = runCatching {
        if (!logFile.exists()) return ""
        val lines = logFile.readLines()
        lines.takeLast(12).joinToString("\n")
    }.getOrDefault("")

    companion object {
        fun isPortFree(port: Int): Boolean = runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", port), 1)
            }
            true
        }.getOrDefault(false)

        fun isPortOpen(port: Int): Boolean = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 350)
            }
            true
        }.getOrDefault(false)
    }
}
