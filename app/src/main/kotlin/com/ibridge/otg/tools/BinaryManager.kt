package com.ibridge.otg.tools

import android.content.Context
import android.net.Uri
import android.system.Os
import com.ibridge.otg.BuildConfig
import com.ibridge.otg.core.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Releases the static arm64 binaries shipped in `assets/bin/arm64-v8a` into the app private
 * directory, marks them executable and validates that they are real aarch64 ELF files.
 *
 * Users can also import an externally built tool bundle (zip) from Settings.
 */
class BinaryManager(
    private val context: Context,
    private val logs: LogBus,
) {

    val toolDir: File = File(context.filesDir, "tools/arm64-v8a")
    val workDir: File = File(context.filesDir, "work").apply { mkdirs() }
    val homeDir: File = File(context.filesDir, "home").apply { mkdirs() }
    val tmpDir: File = File(context.filesDir, "tmp").apply { mkdirs() }
    val lockdownDir: File = File(context.filesDir, "lockdown").apply { mkdirs() }

    private val versionFile = File(context.filesDir, "tools/.asset_version")
    private val mutex = Mutex()

    @Volatile
    private var stagedDir: String? = null

    @Volatile
    private var stagingFailed = false

    private val _status = MutableStateFlow<List<ToolStatus>>(emptyList())
    val status: StateFlow<List<ToolStatus>> = _status.asStateFlow()

    val readyCount: Int get() = _status.value.count { it.installed }
    val allReady: Boolean get() = _status.value.isNotEmpty() && _status.value.all { it.installed }
    val missing: List<ToolId> get() = _status.value.filterNot { it.installed }.map { it.id }

    suspend fun extractAll(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            toolDir.mkdirs()
            workDir.mkdirs()

            val stamp = installStamp()
            val needsExtract = force || !versionFile.exists() || versionFile.readText() != stamp

            if (needsExtract) {
                val assets = runCatching { context.assets.list(ASSET_DIR)?.toList().orEmpty() }
                    .getOrDefault(emptyList())
                    .filter { it != ".gitkeep" && !it.endsWith(".md") && it != "manifest.json" }

                if (assets.isEmpty()) {
                    logs.w(
                        "Toolchain",
                        "assets/$ASSET_DIR 中未发现二进制，请在 Settings 导入工具包或重新打包 APK",
                    )
                } else {
                    assets.forEach { name ->
                        extractAsset(name)
                    }
                }
                versionFile.parentFile?.mkdirs()
                versionFile.writeText(stamp)
            }
            refreshLocked()
            true
        }
    }

    private fun extractAsset(name: String) {
        val dest = File(toolDir, name)
        val tmp = File(toolDir, ".$name.tmp")
        runCatching {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            val elf = inspectArm64Elf(tmp)
            if (!elf.valid) {
                logs.w("Toolchain", "$name 不是有效的 arm64 ELF：${elf.reason}")
                tmp.delete()
                return
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            makeExecutable(dest)
            logs.i("Toolchain", "已释放 $name (${dest.length() / 1024} KB)")
        }.onFailure {
            runCatching { tmp.delete() }
            logs.e("Toolchain", "释放 $name 失败：${it.message}")
        }
    }

    suspend fun importZip(uri: Uri): ToolImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            toolDir.mkdirs()
            val imported = mutableListOf<String>()
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val name = File(entry.name).name
                                val isKnownTool = name in ToolId.required.map { it.fileName }
                                val isSharedLib = name.contains(".so")
                                if (isKnownTool || isSharedLib) {
                                    val tmp = File(toolDir, ".$name.tmp")
                                    tmp.outputStream().use { out ->
                                        val buffer = ByteArray(64 * 1024)
                                        var total = 0L
                                        while (true) {
                                            val read = zip.read(buffer)
                                            if (read <= 0) break
                                            total += read
                                            if (total > MAX_TOOL_BYTES) {
                                                throw IllegalStateException("$name 超过大小限制")
                                            }
                                            out.write(buffer, 0, read)
                                        }
                                    }
                                    val elf = inspectArm64Elf(tmp)
                                    if (!elf.valid) {
                                        logs.w("Toolchain", "跳过 $name：${elf.reason}")
                                        tmp.delete()
                                    } else {
                                        val dest = File(toolDir, name)
                                        if (dest.exists()) dest.delete()
                                        tmp.renameTo(dest)
                                        makeExecutable(dest)
                                        imported += name
                                        logs.i("Toolchain", "已导入 $name (${dest.length() / 1024} KB)")
                                    }
                                } else {
                                    logs.v("Toolchain", "忽略工具包条目 ${entry.name}")
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            }.onFailure {
                logs.e("Toolchain", "导入工具包失败：${it.message}")
            }

            versionFile.parentFile?.mkdirs()
            versionFile.writeText(installStamp())
            refreshLocked()
            ToolImportResult(imported, missing)
        }
    }

    @Suppress("DEPRECATION")
    private fun installStamp(): String {
        val lastUpdate = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return "v${BuildConfig.VERSION_CODE}-$lastUpdate"
    }

    fun refresh() {
        synchronized(this) { refreshLocked() }
    }

    private fun refreshLocked() {
        val list = ToolId.required.map { id ->
            val file = File(toolDir, id.fileName)
            ToolStatus(
                id = id,
                installed = file.isFile && file.length() > 0 && file.canExecute(),
                sizeBytes = if (file.isFile) file.length() else 0L,
                path = if (file.isFile) file.absolutePath else null,
            )
        }
        _status.value = list
    }

    fun pathOf(id: ToolId): String? = _status.value.firstOrNull { it.id == id }
        ?.takeIf { it.installed }
        ?.path

    fun fileOf(id: ToolId): File? = pathOf(id)?.let(::File)

    /**
     * Some ROMs deny executing files from the app sandbox even for root (W^X / SELinux).
     * Copy the toolchain to /data/local/tmp once and execute from there instead.
     */
    suspend fun stageForExecution(root: com.ibridge.otg.core.RootShell): String? {
        stagedDir?.let { return it }
        if (stagingFailed) return null
        return withContext(Dispatchers.IO) {
            val knownNames = ToolId.entries.mapTo(mutableSetOf()) { it.fileName }
            val files = toolDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 && (it.name in knownNames || it.name.contains(".so")) }
                .orEmpty()
            if (files.isEmpty()) {
                stagingFailed = true
                return@withContext null
            }
            val dir = "/data/local/tmp/.ibridge/${BuildConfig.VERSION_CODE}"
            val q = com.ibridge.otg.core.RootShell::quote
            val script = buildString {
                append("mkdir -p ").append(q(dir)).append("; ")
                append("cp -f ")
                files.forEach { append(q(it.absolutePath)).append(' ') }
                append(q(dir)).append("/; ")
                append("chmod 755 ")
                files.forEach { append(q("$dir/${it.name}")).append(' ') }
                append("; echo ").append(q(dir))
            }
            val result = root.run(script, timeoutMs = 30_000)
            if (result.ok) {
                stagedDir = dir
                logs.w("Toolchain", "私有目录执行受限，已切换到 $dir")
                dir
            } else {
                stagingFailed = true
                logs.e("Toolchain", "暂存到 /data/local/tmp 失败：${result.combined.take(300)}")
                null
            }
        }
    }

    private fun makeExecutable(file: File) {
        runCatching { file.setReadable(true, false) }
        runCatching { file.setExecutable(true, false) }
        runCatching { Os.chmod(file.absolutePath, 493) } // 0755
    }

    private fun inspectArm64Elf(file: File): Arm64ElfInfo {
        if (!file.isFile || file.length() < 20) {
            return Arm64ElfInfo(false, "文件为空或过小")
        }
        val header = runCatching { file.inputStream().use { it.readHeader(20) } }
            .getOrElse { return Arm64ElfInfo(false, "无法读取：${it.message}") }
        if (header.size < 20 ||
            header[0] != 0x7F.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) {
            return Arm64ElfInfo(false, "缺少 ELF 魔数")
        }
        val littleEndian = header[5] == 1.toByte()
        val machine = if (littleEndian) {
            (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        } else {
            ((header[18].toInt() and 0xFF) shl 8) or (header[19].toInt() and 0xFF)
        }
        if (machine != 183) { // EM_AARCH64
            return Arm64ElfInfo(false, "不是 arm64-v8a（e_machine=$machine）")
        }
        return Arm64ElfInfo(true, "ok")
    }

    companion object {
        private const val ASSET_DIR = "bin/arm64-v8a"
        private const val MAX_TOOL_BYTES = 96L * 1024 * 1024
    }
}

private fun java.io.InputStream.readHeader(size: Int): ByteArray {
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(buffer, offset, size - offset)
        if (read < 0) break
        offset += read
    }
    return if (offset == size) buffer else buffer.copyOf(offset)
}
