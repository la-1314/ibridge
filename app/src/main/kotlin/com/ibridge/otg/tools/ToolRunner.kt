package com.ibridge.otg.tools

import com.ibridge.otg.core.AppPrefs
import com.ibridge.otg.core.LogBus
import com.ibridge.otg.core.RootShell
import com.ibridge.otg.core.ShellResult

/**
 * Executes one of the bundled arm64 tools through `su`, with the environment expected by
 * libimobiledevice (USBMUXD_SOCKET_ADDRESS) and the app-private tool directory on PATH.
 *
 * If the ROM blocks execution from the app sandbox even for root, the toolchain is staged once to
 * /data/local/tmp and the command is retried from there.
 */
class ToolRunner(
    private val binaries: BinaryManager,
    private val root: RootShell,
    private val prefs: AppPrefs,
    private val logs: LogBus,
) {

    @Volatile
    private var runtimeEnvReady = false

    suspend fun exec(
        tool: ToolId,
        args: List<String>,
        timeoutMs: Long = 60_000,
        onStdoutLine: ((String) -> Unit)? = null,
    ): ShellResult {
        ensureRuntimeEnv()
        val path = binaries.pathOf(tool)
        if (path == null) {
            logs.e("Exec", "缺少 ${tool.fileName}，请先在设置中导入工具包")
            return ShellResult(127, "", "缺少 ${tool.fileName}", false)
        }

        logs.v("Exec", "${tool.fileName} ${args.joinToString(" ")}")
        var result = root.run(
            buildScript(path, args, binaries.toolDir.absolutePath),
            timeoutMs,
            onStdoutLine,
        )

        if (isExecutionDenied(result)) {
            val staged = binaries.stageForExecution(root)
            if (staged != null) {
                val stagedPath = "$staged/${tool.fileName}"
                logs.w("Exec", "重试 ${tool.fileName}：$stagedPath")
                result = root.run(
                    buildScript(stagedPath, args, staged),
                    timeoutMs,
                    onStdoutLine,
                )
            }
        }

        if (!result.ok) {
            logs.w(
                "Exec",
                "${tool.fileName} 退出码=${result.code}" + if (result.timedOut) "（超时）" else "",
            )
        }
        return result
    }

    private fun buildScript(path: String, args: List<String>, binDir: String): String = buildString {
        append("export HOME=").append(RootShell.quote(binaries.homeDir.absolutePath)).append("; ")
        append("export TMPDIR=").append(RootShell.quote(binaries.tmpDir.absolutePath)).append("; ")
        append("export IBRIDGE_LOCKDOWN_DIR=")
        append(RootShell.quote(binaries.lockdownDir.absolutePath)).append("; ")
        append("export USBMUXD_SOCKET_ADDRESS=127.0.0.1:")
        append(prefs.daemonPort)
        append("; ")
        append("export LD_LIBRARY_PATH=")
        append(RootShell.quote(binDir))
        append(":\$LD_LIBRARY_PATH; ")
        append("export PATH=")
        append(RootShell.quote(binDir))
        append(":\$PATH; ")
        append("cd ")
        append(RootShell.quote(binaries.workDir.absolutePath))
        append("; ")
        append("exec ")
        append(RootShell.quote(path))
        args.forEach { append(' ').append(RootShell.quote(it)) }
    }

    private fun isExecutionDenied(result: ShellResult): Boolean =
        result.code == 126 ||
            result.combined.contains("Permission denied", ignoreCase = true) ||
            result.combined.contains("not executable", ignoreCase = true)

    /**
     * Linux-built libimobiledevice keeps pair records in /var/lib/lockdown, which does not exist
     * on Android. Point it at a writable app-private directory when the root filesystem permits.
     */
    private suspend fun ensureRuntimeEnv() {
        if (runtimeEnvReady) return
        val q = RootShell::quote
        val script = buildString {
            append("mkdir -p ").append(q(binaries.homeDir.absolutePath)).append(' ')
            append(q(binaries.tmpDir.absolutePath)).append(' ')
            append(q(binaries.lockdownDir.absolutePath)).append("; ")
            append("mkdir -p /var/lib 2>/dev/null; ")
            append("[ -e /var/lib/lockdown ] || ln -s ")
            append(q(binaries.lockdownDir.absolutePath))
            append(" /var/lib/lockdown 2>/dev/null; true")
        }
        val result = root.run(script, timeoutMs = 15_000)
        runtimeEnvReady = result.ok
    }

    suspend fun execRaw(script: String, timeoutMs: Long = 60_000): ShellResult =
        root.run(script, timeoutMs)
}
