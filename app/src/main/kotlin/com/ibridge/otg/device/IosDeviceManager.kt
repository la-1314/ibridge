package com.ibridge.otg.device

import com.ibridge.otg.core.AppPrefs
import com.ibridge.otg.core.LogBus
import com.ibridge.otg.tools.ToolId
import com.ibridge.otg.tools.ToolRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class DeviceInfo(
    val udid: String,
    val name: String,
    val productType: String,
    val productVersion: String,
    val buildVersion: String,
    val serial: String,
    val modelNumber: String,
    val activationState: String,
    val paired: Boolean,
) {
    val displayName: String
        get() = name.ifBlank { productType.ifBlank { "iOS 设备" } }

    val subtitle: String
        get() = listOf(
            productType.ifBlank { "iPhone" },
            if (productVersion.isBlank()) "" else "iOS $productVersion",
        ).filter { it.isNotBlank() }.joinToString(" · ")
}

sealed interface DevicePhase {
    data object NoUsb : DevicePhase
    data object DaemonDown : DevicePhase
    data object Searching : DevicePhase
    data class NotPaired(val info: DeviceInfo) : DevicePhase
    data class Ready(val info: DeviceInfo) : DevicePhase
    data class Error(val message: String) : DevicePhase

    val infoOrNull: DeviceInfo?
        get() = when (this) {
            is Ready -> info
            is NotPaired -> info
            else -> null
        }
}

data class IosAppInfo(
    val bundleId: String,
    val name: String,
    val version: String,
    val buildVersion: String,
    val type: String,
) {
    val initial: String get() = name.firstOrNull()?.uppercase() ?: "?"
}

enum class InstallerMode { MODERN, LEGACY }

class IosDeviceManager(
    private val runner: ToolRunner,
    private val prefs: AppPrefs,
    private val logs: LogBus,
) {

    private val refreshMutex = Mutex()
    private val listMutex = Mutex()

    private val _phase = MutableStateFlow<DevicePhase>(DevicePhase.NoUsb)
    val phase: StateFlow<DevicePhase> = _phase.asStateFlow()

    private val _apps = MutableStateFlow<List<IosAppInfo>>(emptyList())
    val apps: StateFlow<List<IosAppInfo>> = _apps.asStateFlow()

    private val _listing = MutableStateFlow(false)
    val listing: StateFlow<Boolean> = _listing.asStateFlow()

    @Volatile
    var currentUdid: String? = null
        private set

    @Volatile
    private var installerMode: InstallerMode? = null

    @Volatile
    private var appsLoadedFor: String? = null

    @Volatile
    private var lastPhaseSignature: String = ""

    suspend fun refresh(
        usbAttached: Boolean,
        daemonReady: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (!usbAttached) {
            currentUdid = null
            appsLoadedFor = null
            _apps.value = emptyList()
            publish(DevicePhase.NoUsb)
            return@withContext
        }
        if (!daemonReady) {
            publish(DevicePhase.DaemonDown)
            return@withContext
        }

        refreshMutex.withLock {
            val idResult = runner.exec(ToolId.IDEVICE_ID, listOf("-l"), timeoutMs = 15_000)
            if (idResult.timedOut) {
                publish(DevicePhase.Error("idevice_id 超时"))
                return@withLock
            }
            if (!idResult.ok) {
                publish(DevicePhase.Error(idResult.combined.trim().ifBlank { "idevice_id 执行失败" }))
                return@withLock
            }

            val udid = idResult.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
            if (udid == null) {
                currentUdid = null
                publish(DevicePhase.Searching)
                return@withLock
            }

            currentUdid = udid
            prefs.lastUdId = udid

            val validate = runner.exec(
                ToolId.IDEVICEPAIR,
                listOf("validate", "-u", udid),
                timeoutMs = 20_000,
            )
            val paired = validate.code == 0 ||
                validate.combined.contains("SUCCESS", ignoreCase = true)

            var infoResult = if (paired) {
                runner.exec(
                    ToolId.IDEVICEINFO,
                    listOf("-u", udid),
                    timeoutMs = 25_000,
                )
            } else {
                // Simple mode does not trigger an automatic pairing attempt.
                runner.exec(
                    ToolId.IDEVICEINFO,
                    listOf("-s", "-u", udid),
                    timeoutMs = 15_000,
                )
            }
            if (!infoResult.ok) {
                // Simple mode works without a pairing record and still exposes basic identity.
                val simple = runner.exec(
                    ToolId.IDEVICEINFO,
                    listOf("-s", "-u", udid),
                    timeoutMs = 15_000,
                )
                if (simple.stdout.isNotBlank()) infoResult = simple
            }

            val info = parseInfo(infoResult.stdout, udid, paired)
            val next = when {
                !paired -> DevicePhase.NotPaired(info)
                !infoResult.ok && info.name.isBlank() -> DevicePhase.Error(
                    infoResult.combined.trim().ifBlank { "读取设备信息失败" },
                )

                else -> DevicePhase.Ready(info)
            }
            publish(next)

            if (next is DevicePhase.Ready && paired && appsLoadedFor != udid) {
                // Populate the app list on first successful connection.
                listAppsInternal(udid, silent = true)
            }
        }
    }

    suspend fun pair(): Result<Unit> = withContext(Dispatchers.IO) {
        val udid = currentUdid
            ?: return@withContext Result.failure(IllegalStateException("未发现设备"))
        logs.i("Pair", "请求配对，请在 iPhone 上点击“信任”并输入锁屏密码")
        val result = runner.exec(
            ToolId.IDEVICEPAIR,
            listOf("pair", "-u", udid),
            timeoutMs = 90_000,
        )
        val output = result.combined.trim()
        if (result.ok && !output.contains("ERROR", ignoreCase = true)) {
            logs.i("Pair", output.ifBlank { "配对成功" })
            Result.success(Unit)
        } else {
            val message = output.ifBlank { "配对失败（退出码 ${result.code}）" }
            logs.e("Pair", message)
            Result.failure(IllegalStateException(message))
        }
    }

    suspend fun listApps(): Result<List<IosAppInfo>> = withContext(Dispatchers.IO) {
        val udid = currentUdid
            ?: return@withContext Result.failure(IllegalStateException("请先连接并配对设备"))
        try {
            Result.success(listAppsInternal(udid, silent = false))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun listAppsInternal(udid: String, silent: Boolean): List<IosAppInfo> =
        listMutex.withLock {
            if (!silent) _listing.value = true
            try {
                val mode = detectInstallerMode()
                val args = when (mode) {
                    InstallerMode.MODERN -> listOf("-u", udid, "list", "--user", "--xml")
                    InstallerMode.LEGACY -> listOf("-u", udid, "-l")
                }
                val result = runner.exec(ToolId.IDEVICEINSTALLER, args, timeoutMs = 120_000)
                if (!result.ok) {
                    logs.w(
                        "Apps",
                        "列出应用失败：${result.combined.trim().take(400).ifBlank { "退出码 ${result.code}" }}",
                    )
                    return@withLock emptyList()
                }

                val parsed = PlistParser.parseArrayOfDicts(result.stdout)
                    .mapNotNull { map -> map.toApp() }
                    .ifEmpty { parseLegacyAppList(result.stdout) }
                    .distinctBy { it.bundleId }
                    .sortedBy { it.name.lowercase() }

                _apps.value = parsed
                appsLoadedFor = udid
                if (!silent) logs.i("Apps", "已获取 ${parsed.size} 个应用")
                parsed
            } finally {
                _listing.value = false
            }
        }

    suspend fun install(ipa: File, onProgress: (Float?, String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val udid = currentUdid
                ?: return@withContext Result.failure(IllegalStateException("请先连接并配对设备"))
            if (!ipa.isFile || ipa.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("IPA 文件不存在或为空"))
            }

            val mode = detectInstallerMode()
            val args = when (mode) {
                InstallerMode.MODERN -> listOf(
                    "-u", udid, "-w", "install", ipa.absolutePath,
                )

                InstallerMode.LEGACY -> listOf(
                    "-u", udid, "-w", "-i", ipa.absolutePath,
                )
            }

            logs.i("Install", "开始安装 ${ipa.name} (${ipa.length() / 1024 / 1024} MB)")
            val result = runner.exec(
                ToolId.IDEVICEINSTALLER,
                args,
                timeoutMs = 20 * 60_000,
            ) { line ->
                val progress = parsePercent(line)
                if (progress != null || line.isNotBlank()) {
                    onProgress(progress, line.trim())
                }
                if (line.isNotBlank()) logs.i("ideviceinstaller", line.trim())
            }

            val output = result.combined
            val failed = !result.ok ||
                output.contains("ERROR:", ignoreCase = true) ||
                output.contains("Unable to install", ignoreCase = true)
            if (failed) {
                val message = output.lineSequence()
                    .lastOrNull { it.contains("ERROR", true) || it.contains("failed", true) }
                    ?: output.trim().take(500).ifBlank { "安装失败（退出码 ${result.code}）" }
                logs.e("Install", message)
                Result.failure(IllegalStateException(message))
            } else {
                onProgress(1f, "安装完成")
                logs.i("Install", "安装完成：${ipa.name}")
                listAppsInternal(udid, silent = true)
                Result.success(Unit)
            }
        }

    suspend fun uninstall(bundleId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val udid = currentUdid
            ?: return@withContext Result.failure(IllegalStateException("请先连接并配对设备"))
        val mode = detectInstallerMode()
        val args = when (mode) {
            InstallerMode.MODERN -> listOf("-u", udid, "-w", "uninstall", bundleId)
            InstallerMode.LEGACY -> listOf("-u", udid, "-w", "-U", bundleId)
        }
        logs.i("Uninstall", "卸载 $bundleId")
        val result = runner.exec(ToolId.IDEVICEINSTALLER, args, timeoutMs = 4 * 60_000)
        val output = result.combined
        if (result.ok && !output.contains("ERROR", ignoreCase = true)) {
            _apps.value = _apps.value.filterNot { it.bundleId == bundleId }
            logs.i("Uninstall", "已卸载 $bundleId")
            Result.success(Unit)
        } else {
            val message = output.trim().take(500).ifBlank { "卸载失败（退出码 ${result.code}）" }
            logs.e("Uninstall", message)
            Result.failure(IllegalStateException(message))
        }
    }

    private suspend fun detectInstallerMode(): InstallerMode {
        installerMode?.let { return it }
        val help = runner.exec(ToolId.IDEVICEINSTALLER, listOf("--help"), timeoutMs = 15_000)
        val text = help.combined
        val mode = if (text.contains("COMMANDS:", ignoreCase = true) ||
            text.contains(Regex("^\\s+list\\s+", RegexOption.MULTILINE))
        ) {
            InstallerMode.MODERN
        } else {
            InstallerMode.LEGACY
        }
        installerMode = mode
        logs.i("Apps", "ideviceinstaller 接口模式：${if (mode == InstallerMode.MODERN) "子命令" else "传统参数"}")
        return mode
    }

    private fun parseInfo(stdout: String, udid: String, paired: Boolean): DeviceInfo {
        val map = mutableMapOf<String, String>()
        stdout.lineSequence().forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                val key = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()
                if (key.isNotEmpty() && key.first().isLetter() && value.isNotEmpty()) {
                    map.putIfAbsent(key, value)
                }
            }
        }
        return DeviceInfo(
            udid = map["UniqueDeviceID"]?.takeIf { it.isNotBlank() } ?: udid,
            name = map["DeviceName"].orEmpty(),
            productType = map["ProductType"].orEmpty(),
            productVersion = map["ProductVersion"].orEmpty(),
            buildVersion = map["BuildVersion"].orEmpty(),
            serial = map["SerialNumber"].orEmpty(),
            modelNumber = map["ModelNumber"].orEmpty(),
            activationState = map["ActivationState"].orEmpty(),
            paired = paired,
        )
    }

    private fun Map<String, String>.toApp(): IosAppInfo? {
        val bundleId = this["CFBundleIdentifier"]?.takeIf { it.isNotBlank() } ?: return null
        val name = this["CFBundleDisplayName"]
            ?.takeIf { it.isNotBlank() }
            ?: this["CFBundleName"]?.takeIf { it.isNotBlank() }
            ?: bundleId
        return IosAppInfo(
            bundleId = bundleId,
            name = name,
            version = this["CFBundleShortVersionString"].orEmpty(),
            buildVersion = this["CFBundleVersion"].orEmpty(),
            type = this["ApplicationType"].orEmpty(),
        )
    }

    private fun parseLegacyAppList(text: String): List<IosAppInfo> {
        val regex = Regex("""^(\S+)\s+-\s+(.+?)(?:\s+(\d+(?:\.\d+)*))?$""")
        return text.lineSequence().mapNotNull { line ->
            val match = regex.matchEntire(line.trim()) ?: return@mapNotNull null
            val bundleId = match.groupValues[1]
            if (bundleId.count { it == '.' } < 1) return@mapNotNull null
            IosAppInfo(
                bundleId = bundleId,
                name = match.groupValues[2].trim(),
                version = match.groupValues[3],
                buildVersion = "",
                type = "",
            )
        }.toList()
    }

    private fun parsePercent(line: String): Float? {
        val match = PERCENT_REGEX.find(line) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        return (value / 100f).coerceIn(0f, 1f)
    }

    private fun publish(phase: DevicePhase) {
        val signature = when (phase) {
            DevicePhase.NoUsb -> "no_usb"
            DevicePhase.DaemonDown -> "daemon_down"
            DevicePhase.Searching -> "searching"
            is DevicePhase.Ready -> "ready:${phase.info.udid}:${phase.info.productVersion}"
            is DevicePhase.NotPaired -> "unpaired:${phase.info.udid}"
            is DevicePhase.Error -> "error:${phase.message}"
        }
        _phase.value = phase
        if (signature != lastPhaseSignature) {
            lastPhaseSignature = signature
            when (phase) {
                is DevicePhase.Ready ->
                    logs.i("Device", "已连接 ${phase.info.displayName} (${phase.info.subtitle})")

                is DevicePhase.NotPaired ->
                    logs.w("Device", "设备未配对：${phase.info.udid}")

                is DevicePhase.Error -> logs.e("Device", "设备错误：${phase.message}")
                DevicePhase.Searching -> logs.w("Device", "USB 已连接，但尚未发现可用的 iOS 设备")
                else -> Unit
            }
        }
    }

    companion object {
        private val PERCENT_REGEX = Regex("""(\d{1,3})\s*%""")
    }
}
