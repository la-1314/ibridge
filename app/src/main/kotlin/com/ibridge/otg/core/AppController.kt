package com.ibridge.otg.core

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.ibridge.otg.BuildConfig
import com.ibridge.otg.daemon.DaemonState
import com.ibridge.otg.daemon.UsbmuxdManager
import com.ibridge.otg.device.DevicePhase
import com.ibridge.otg.device.IosAppInfo
import com.ibridge.otg.device.IosDeviceManager
import com.ibridge.otg.sign.IpaSigner
import com.ibridge.otg.sign.SignInputs
import com.ibridge.otg.sign.SignState
import com.ibridge.otg.tools.BinaryManager
import com.ibridge.otg.tools.ToolId
import com.ibridge.otg.tools.ToolRunner
import com.ibridge.otg.usb.AppleUsbDevice
import com.ibridge.otg.usb.UsbMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class OperationState(
    val title: String,
    val detail: String = "",
    val progress: Float? = null,
)

data class UiMessage(val text: String, val isError: Boolean = false)

enum class SignFileKind { IPA, P12, PROVISION }

class AppController private constructor(val app: Application) {

    val prefs = AppPrefs(app)
    val logs = LogBus(app)
    val binaries = BinaryManager(app, logs)
    val root = RootShell(logs)
    val runner = ToolRunner(binaries, root, prefs, logs)
    val usb = UsbMonitor(app, logs)
    val daemon = UsbmuxdManager(app, prefs, binaries, root, logs)
    val device = IosDeviceManager(runner, prefs, logs)

    private val inputDir = File(app.filesDir, "input").apply { mkdirs() }
    private val outputDir = File(app.filesDir, "output").apply { mkdirs() }
    private val signer = IpaSigner(runner, logs, outputDir)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    private val _rootState = MutableStateFlow<RootState>(RootState.Unknown)
    val rootState: StateFlow<RootState> = _rootState.asStateFlow()

    private val _operation = MutableStateFlow<OperationState?>(null)
    val operation: StateFlow<OperationState?> = _operation.asStateFlow()

    private val _signInputs = MutableStateFlow(SignInputs())
    val signInputs: StateFlow<SignInputs> = _signInputs.asStateFlow()

    private val _signState = MutableStateFlow(SignState())
    val signState: StateFlow<SignState> = _signState.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()

    val devicePhase: StateFlow<DevicePhase> = device.phase
    val apps: StateFlow<List<IosAppInfo>> = device.apps
    val daemonState: StateFlow<DaemonState> = daemon.state
    val tools: StateFlow<List<com.ibridge.otg.tools.ToolStatus>> = binaries.status
    val usbDevices: StateFlow<List<AppleUsbDevice>> = usb.devices
    val listingApps: StateFlow<Boolean> = device.listing

    fun boot() {
        scope.launch {
            logs.i(
                "Boot",
                "iBridge ${BuildConfig.VERSION_NAME} 启动 · Android ${Build.VERSION.RELEASE} · " +
                    "${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
            )
            binaries.extractAll()
            _rootState.value = root.check()
            daemon.refresh()
            if (_rootState.value == RootState.Granted && prefs.autoStartDaemon.value) {
                daemon.start()
            }
            device.refresh(usb.devices.value.isNotEmpty(), daemon.state.value is DaemonState.Running)
            startWatcher()
        }
    }

    private fun startWatcher() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            var lastAutoStart = 0L
            while (isActive) {
                val attached = usb.devices.value.isNotEmpty()
                val now = System.currentTimeMillis()
                if (
                    attached &&
                    root.state == RootState.Granted &&
                    prefs.autoStartDaemon.value &&
                    binaries.pathOf(ToolId.USBMUXD) != null &&
                    daemon.state.value !is DaemonState.Running &&
                    now - lastAutoStart > 20_000
                ) {
                    lastAutoStart = now
                    daemon.start()
                }
                if (_operation.value == null) {
                    device.refresh(attached, daemon.state.value is DaemonState.Running)
                }
                delay(if (attached) 3_500 else 7_000)
            }
        }
    }

    fun refreshAll() {
        scope.launch {
            usb.refresh()
            daemon.refresh()
            device.refresh(usb.devices.value.isNotEmpty(), daemon.state.value is DaemonState.Running)
        }
    }

    fun requestUsbPermission(device: AppleUsbDevice) = usb.requestPermission(device)

    fun checkRoot() {
        scope.launch {
            _rootState.value = root.check()
            if (_rootState.value == RootState.Granted && daemon.state.value !is DaemonState.Running) {
                daemon.start()
            }
        }
    }

    fun startDaemon() = launchOperation("启动 usbmuxd") {
        if (_rootState.value != RootState.Granted) _rootState.value = root.check()
        val ok = daemon.start()
        if (ok) toast("usbmuxd 已启动") else toast(failureMessage(daemon.state.value, "启动失败"), true)
        device.refresh(usb.devices.value.isNotEmpty(), daemon.state.value is DaemonState.Running)
    }

    fun stopDaemon() = launchOperation("停止 usbmuxd") {
        daemon.stop()
        toast("usbmuxd 已停止")
    }

    fun restartDaemon() = launchOperation("重启 usbmuxd") {
        if (_rootState.value != RootState.Granted) _rootState.value = root.check()
        val ok = daemon.restart()
        if (ok) toast("usbmuxd 已重启") else toast(failureMessage(daemon.state.value, "重启失败"), true)
        device.refresh(usb.devices.value.isNotEmpty(), daemon.state.value is DaemonState.Running)
    }

    fun reallocatePort() = launchOperation("重新分配端口") {
        val wasRunning = daemon.state.value is DaemonState.Running
        if (wasRunning) daemon.stop()
        val port = daemon.reallocatePort()
        if (port <= 0) {
            toast("没有可用端口", true)
            return@launchOperation
        }
        logs.i("usbmuxd", "端口已切换为 $port")
        if (wasRunning) daemon.start()
        toast("usbmuxd 端口：$port")
    }

    fun refreshDevice() {
        scope.launch {
            device.refresh(usb.devices.value.isNotEmpty(), daemon.state.value is DaemonState.Running)
        }
    }

    fun pair() = launchOperation("配对 iPhone") {
        val phase = device.phase.value
        if (phase !is DevicePhase.NotPaired && phase !is DevicePhase.Ready) {
            toast("未发现可配对的设备", true)
            return@launchOperation
        }
        if (daemon.state.value !is DaemonState.Running && !daemon.start()) {
            toast("usbmuxd 未运行，无法配对", true)
            return@launchOperation
        }
        updateOperation("请在 iPhone 上点击“信任”…")
        val result = device.pair()
        result.onSuccess {
            toast("配对成功")
            device.refresh(usb.devices.value.isNotEmpty(), true)
        }.onFailure {
            toast("配对失败：${it.message}", true)
        }
    }

    fun listApps() = launchOperation("读取应用列表") {
        val result = device.listApps()
        result.onSuccess { toast("共 ${it.size} 个应用") }
            .onFailure { toast("读取失败：${it.message}", true) }
    }

    fun installFromUri(uri: Uri) = launchOperation("安装 IPA") {
        val file = copyToInput(uri, "ipa", setOf("ipa")) ?: return@launchOperation
        installInternal(file)
    }

    fun installSigned() {
        val file = _signState.value.output ?: return
        scope.launch {
            if (_operation.value != null) {
                toast("已有任务进行中", true)
                return@launch
            }
            _operation.value = OperationState("安装签名后的 IPA")
            try {
                installInternal(file)
            } finally {
                _operation.value = null
            }
        }
    }

    private suspend fun installInternal(file: File) {
        if (device.phase.value !is DevicePhase.Ready) {
            toast("设备未就绪，请先连接并配对", true)
            return
        }
        if (daemon.state.value !is DaemonState.Running && !daemon.start()) {
            toast("usbmuxd 未运行，无法安装", true)
            return
        }
        updateOperation("正在推送 ${file.name}", null)
        val result = device.install(file) { progress, line ->
            updateOperation(line.ifBlank { "安装中…" }.take(90), progress)
        }
        result.onSuccess {
            toast("安装完成")
            refreshDevice()
        }.onFailure {
            toast("安装失败：${it.message}", true)
        }
    }

    fun uninstall(bundleId: String) = launchOperation("卸载应用") {
        updateOperation(bundleId)
        val result = device.uninstall(bundleId)
        result.onSuccess { toast("已卸载 $bundleId") }
            .onFailure { toast("卸载失败：${it.message}", true) }
    }

    fun selectSignFile(kind: SignFileKind, uri: Uri) = launchOperation("读取文件") {
        val (prefix, extensions) = when (kind) {
            SignFileKind.IPA -> "ipa" to setOf("ipa")
            SignFileKind.P12 -> "cert" to setOf("p12", "pfx")
            SignFileKind.PROVISION -> "profile" to setOf("mobileprovision", "provision")
        }
        val file = copyToInput(uri, prefix, extensions) ?: return@launchOperation
        _signInputs.value = when (kind) {
            SignFileKind.IPA -> _signInputs.value.copy(ipa = file)
            SignFileKind.P12 -> _signInputs.value.copy(p12 = file)
            SignFileKind.PROVISION -> _signInputs.value.copy(provision = file)
        }
        _signState.value = SignState()
    }

    fun updateSignPassword(password: String) {
        _signInputs.value = _signInputs.value.copy(password = password)
    }

    fun updateBundleId(bundleId: String) {
        _signInputs.value = _signInputs.value.copy(bundleId = bundleId)
    }

    fun sign() {
        val inputs = _signInputs.value
        if (!inputs.ready) {
            toast("请选择 IPA、p12 与 mobileprovision", true)
            return
        }
        launchOperation("签名 IPA") {
            _signState.value = SignState(running = true, message = "准备签名…")
            updateOperation("zsign 正在签名…")
            val result = signer.sign(inputs) { progress, line ->
                _signState.value = _signState.value.copy(
                    progress = progress,
                    message = line.take(120),
                )
                updateOperation(line.take(90), progress)
            }
            result.onSuccess { output ->
                _signState.value = SignState(
                    running = false,
                    progress = 1f,
                    message = "签名完成",
                    output = output,
                )
                toast("签名完成，可直接安装")
            }.onFailure { error ->
                _signState.value = SignState(running = false, error = error.message)
                toast("签名失败：${error.message}", true)
            }
        }
    }

    fun reExtractTools() = launchOperation("释放内置工具") {
        binaries.extractAll(force = true)
        val missing = binaries.missing
        if (missing.isEmpty()) {
            toast("工具链已就绪")
            maybeStartDaemon()
        } else {
            toast("仍缺少：${missing.joinToString { it.fileName }}", true)
        }
    }

    fun importTools(uri: Uri) = launchOperation("导入工具包") {
        val result = binaries.importZip(uri)
        if (result.imported.isEmpty()) {
            toast("未导入任何工具，请检查 zip 内的 arm64 二进制", true)
        } else if (result.missing.isEmpty()) {
            toast("已导入 ${result.imported.size} 个工具")
            maybeStartDaemon()
        } else {
            toast("已导入 ${result.imported.size} 个，仍缺少 ${result.missing.size} 个", true)
        }
    }

    private suspend fun maybeStartDaemon() {
        if (
            root.state == RootState.Granted &&
            usb.devices.value.isNotEmpty() &&
            daemon.state.value !is DaemonState.Running
        ) {
            daemon.start()
        }
    }

    fun removeSignOutput() {
        _signState.value.output?.delete()
        _signState.value = SignState()
    }

    fun saveLogs(uri: Uri) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                logs.saveTo(uri, app.contentResolver)
            }
            toast(if (ok) "日志已保存" else "日志保存失败", !ok)
        }
    }

    fun clearLogs() {
        logs.clear()
        logs.i("Logs", "日志已清空")
        toast("日志已清空")
    }

    fun clearCache() = launchOperation("清理缓存") {
        val deleted = withContext(Dispatchers.IO) {
            var count = 0
            inputDir.listFiles()?.forEach { if (it.delete()) count++ }
            outputDir.listFiles()?.forEach { if (it.delete()) count++ }
            count
        }
        _signInputs.value = SignInputs()
        _signState.value = SignState()
        toast("已清理 $deleted 个缓存文件")
    }

    fun stopDaemonOnExitIfNeeded() {
        if (!prefs.stopDaemonOnExit.value) return
        runBlocking {
            withTimeoutOrNull(3_000) { daemon.stop() }
        }
    }

    fun shutdown() {
        usb.close()
        scope.cancel()
    }

    private suspend fun copyToInput(
        uri: Uri,
        prefix: String,
        allowedExtensions: Set<String>,
    ): File? = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: "$prefix-${System.currentTimeMillis()}"
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension !in allowedExtensions) {
            toast("请选择 ${allowedExtensions.joinToString(" / ") { ".$it" }} 文件", true)
            return@withContext null
        }
        val safeName = displayName
            .replace(Regex("[^A-Za-z0-9._\\-() \\u4e00-\\u9fa5]"), "_")
            .take(64)
            .ifBlank { "$prefix-${System.currentTimeMillis()}.$extension" }
        val dest = File(inputDir, "$prefix-${System.currentTimeMillis()}-$safeName")
        updateOperation("正在读取 $displayName")
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: error("无法打开输入流")
        }.onFailure {
            dest.delete()
            logs.e("File", "读取文件失败：${it.message}")
            toast("读取文件失败：${it.message}", true)
            return@withContext null
        }
        logs.i("File", "已导入 ${dest.name} (${dest.length() / 1024} KB)")
        pruneOldInputs()
        dest
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private fun pruneOldInputs() {
        runCatching {
            val files = inputDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            files.drop(12).forEach { it.delete() }
            val cutoff = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        }
    }

    private fun launchOperation(title: String, block: suspend () -> Unit) {
        scope.launch {
            if (_operation.value != null) {
                toast("已有任务进行中，请稍候", true)
                return@launch
            }
            _operation.value = OperationState(title)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logs.e("Task", "$title 失败：${e.message}")
                toast("$title 失败：${e.message ?: "未知错误"}", true)
            } finally {
                _operation.value = null
            }
        }
    }

    private fun updateOperation(detail: String, progress: Float? = null) {
        val current = _operation.value ?: return
        _operation.value = current.copy(detail = detail, progress = progress)
    }

    private fun toast(text: String, error: Boolean = false) {
        if (error) logs.w("UI", text) else logs.i("UI", text)
        _messages.tryEmit(UiMessage(text, error))
    }

    private fun failureMessage(state: DaemonState, fallback: String): String =
        (state as? DaemonState.Failed)?.message ?: fallback

    companion object {
        @Volatile
        private var instance: AppController? = null

        fun get(context: Context): AppController {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AppController(context.applicationContext as Application)
                    .also {
                        instance = it
                        it.boot()
                    }
            }
        }

        fun toolsSummary(missing: List<ToolId>): String =
            if (missing.isEmpty()) "全部就绪" else "缺少 ${missing.size} 项"
    }
}
