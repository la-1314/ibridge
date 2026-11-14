package com.ibridge.otg.sign

import com.ibridge.otg.core.LogBus
import com.ibridge.otg.tools.ToolId
import com.ibridge.otg.tools.ToolRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SignInputs(
    val ipa: File? = null,
    val p12: File? = null,
    val provision: File? = null,
    val password: String = "",
    val bundleId: String = "",
) {
    val ready: Boolean
        get() = ipa?.isFile == true && p12?.isFile == true && provision?.isFile == true
}

data class SignState(
    val running: Boolean = false,
    val progress: Float? = null,
    val message: String = "",
    val output: File? = null,
    val error: String? = null,
)

class IpaSigner(
    private val runner: ToolRunner,
    private val logs: LogBus,
    private val outputDir: File,
) {

    suspend fun sign(
        inputs: SignInputs,
        onProgress: (Float?, String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val ipa = inputs.ipa
        val p12 = inputs.p12
        val provision = inputs.provision
        if (ipa == null || p12 == null || provision == null) {
            return@withContext Result.failure(IllegalStateException("请先选择 IPA、p12 与描述文件"))
        }
        if (!ipa.isFile || !p12.isFile || !provision.isFile) {
            return@withContext Result.failure(IllegalStateException("签名文件已失效，请重新选择"))
        }
        if (ipa.extension.lowercase() != "ipa") {
            return@withContext Result.failure(IllegalStateException("所选文件不是 .ipa"))
        }
        if (provision.extension.lowercase() !in setOf("mobileprovision", "provision")) {
            return@withContext Result.failure(
                IllegalStateException("描述文件应为 .mobileprovision"),
            )
        }
        if (inputs.bundleId.isNotBlank() && !BUNDLE_ID_REGEX.matches(inputs.bundleId.trim())) {
            return@withContext Result.failure(
                IllegalStateException("BundleID 格式不正确，例如 com.example.app"),
            )
        }

        outputDir.mkdirs()
        val baseName = ipa.nameWithoutExtension.ifBlank { "app" }
        val output = File(
            outputDir,
            "$baseName-signed-${System.currentTimeMillis()}.ipa",
        )

        val args = buildList {
            add("-k")
            add(p12.absolutePath)
            if (inputs.password.isNotEmpty()) {
                add("-p")
                add(inputs.password)
            }
            add("-m")
            add(provision.absolutePath)
            add("-o")
            add(output.absolutePath)
            if (inputs.bundleId.isNotBlank()) {
                add("-b")
                add(inputs.bundleId.trim())
            }
            add(ipa.absolutePath)
        }

        logs.i("Sign", "开始签名 ${ipa.name} -> ${output.name}")
        logs.i(
            "Sign",
            "配置：p12=${p12.name}，描述文件=${provision.name}" +
                if (inputs.bundleId.isBlank()) "" else "，BundleID=${inputs.bundleId.trim()}",
        )

        val result = runner.exec(ToolId.ZSIGN, args, timeoutMs = 15 * 60_000) { line ->
            val progress = parsePercent(line)
            if (progress != null || line.startsWith(">>>")) {
                onProgress(progress, line.trim())
            }
            val level = when {
                line.contains("error", ignoreCase = true) -> com.ibridge.otg.core.LogLevel.ERROR
                line.contains("warning", ignoreCase = true) -> com.ibridge.otg.core.LogLevel.WARN
                else -> com.ibridge.otg.core.LogLevel.INFO
            }
            logs.log(level, "zsign", line.trim())
        }

        val success = result.ok && output.isFile && output.length() > 0
        if (success) {
            onProgress(1f, "签名完成")
            logs.i("Sign", "签名完成：${output.name} (${output.length() / 1024 / 1024} MB)")
            Result.success(output)
        } else {
            if (output.exists()) output.delete()
            val message = result.combined.lineSequence()
                .lastOrNull { it.contains("error", true) || it.contains("fail", true) }
                ?: result.combined.trim().take(600)
                    .ifBlank { "zsign 退出码 ${result.code}" }
            logs.e("Sign", "签名失败：$message")
            Result.failure(IllegalStateException(message))
        }
    }

    private fun parsePercent(line: String): Float? {
        val match = PERCENT_REGEX.find(line) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        return (value / 100f).coerceIn(0f, 1f)
    }

    companion object {
        private val PERCENT_REGEX = Regex("""(\d{1,3})\s*%""")
        private val BUNDLE_ID_REGEX = Regex("""^[A-Za-z0-9][A-Za-z0-9\-]*(\.[A-Za-z0-9][A-Za-z0-9\-]*)+$""")
    }
}
