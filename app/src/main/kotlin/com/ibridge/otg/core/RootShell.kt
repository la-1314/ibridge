package com.ibridge.otg.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

sealed interface RootState {
    data object Unknown : RootState
    data object Granted : RootState
    data class Denied(val reason: String) : RootState
}

data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val combined: String get() = buildString {
        if (stdout.isNotBlank()) append(stdout)
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(stderr)
        }
    }

    val ok: Boolean get() = code == 0 && !timedOut
}

/**
 * Thin wrapper around `su -c <script>`. Every native tool invocation goes through this class so
 * the whole toolchain shares one root-detection / timeout / logging path.
 */
class RootShell(private val logs: LogBus) {

    @Volatile
    var state: RootState = RootState.Unknown
        private set

    suspend fun check(): RootState = withContext(Dispatchers.IO) {
        val result = run("id", timeoutMs = 20_000)
        val granted = result.ok && result.stdout.contains("uid=0")
        val next = if (granted) {
            RootState.Granted
        } else {
            val reason = when {
                result.timedOut -> "获取 Root 超时，请确认授权弹窗"
                result.stderr.isNotBlank() -> result.stderr.trim()
                result.code != 0 -> "su 返回 ${result.code}"
                else -> "su 不可用，请确认设备已 Root"
            }
            RootState.Denied(reason)
        }
        if (next != state) {
            when (next) {
                RootState.Granted -> logs.i("Root", "已获取 Root 权限")
                is RootState.Denied -> logs.w("Root", "Root 不可用：${next.reason}")
                else -> Unit
            }
        }
        state = next
        next
    }

    suspend fun run(
        script: String,
        timeoutMs: Long = 60_000,
        onStdoutLine: ((String) -> Unit)? = null,
    ): ShellResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("su", "-c", script).start()
        } catch (e: IOException) {
            return@withContext ShellResult(-1, "", "无法启动 su：${e.message}", false)
        }

        runCatching { process.outputStream.close() }

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stdout) { stdout.append(line).append('\n') }
                        onStdoutLine?.invoke(line)
                    }
                }
            }
        }.apply { isDaemon = true }

        val errThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderr) { stderr.append(line).append('\n') }
                    }
                }
            }
        }.apply { isDaemon = true }

        outThread.start()
        errThread.start()

        val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)

        if (!finished) {
            runCatching { process.destroyForcibly() }
        }

        runCatching { outThread.join(1_500) }
        runCatching { errThread.join(1_500) }

        ShellResult(
            code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            stdout = synchronized(stdout) { stdout.toString() },
            stderr = synchronized(stderr) { stderr.toString() },
            timedOut = !finished,
        )
    }

    companion object {
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun command(parts: List<String>): String = parts.joinToString(" ") { quote(it) }
    }
}
