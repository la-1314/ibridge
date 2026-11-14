package com.ibridge.otg.tools

enum class ToolId(
    val fileName: String,
    val label: String,
    val description: String,
) {
    USBMUXD("usbmuxd", "usbmuxd", "USB 多路复用守护进程，为 idevice* 工具提供设备通道"),
    IDEVICE_ID("idevice_id", "idevice_id", "枚举已连接的 iOS 设备 UDID"),
    IDEVICEINFO("ideviceinfo", "ideviceinfo", "通过 lockdownd 读取设备信息"),
    IDEVICEPAIR("idevicepair", "idevicepair", "执行 lockdownd 配对 / 校验信任关系"),
    IDEVICEINSTALLER("ideviceinstaller", "ideviceinstaller", "安装、卸载与列出 IPA 应用"),
    ZSIGN("zsign", "zsign", "使用 p12 + mobileprovision 重签 IPA"),
    ;

    companion object {
        val required: List<ToolId> = entries
    }
}

data class ToolStatus(
    val id: ToolId,
    val installed: Boolean,
    val sizeBytes: Long,
    val path: String?,
)

data class ToolImportResult(
    val imported: List<String>,
    val missing: List<ToolId>,
)

data class Arm64ElfInfo(
    val valid: Boolean,
    val reason: String,
)
