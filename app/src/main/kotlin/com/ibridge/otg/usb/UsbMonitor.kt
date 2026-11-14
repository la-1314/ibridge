package com.ibridge.otg.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ibridge.otg.core.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppleUsbDevice(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val productName: String,
    val hasPermission: Boolean,
) {
    val idLabel: String get() = "%04X:%04X".format(vendorId, productId)
}

/**
 * Tracks Apple USB devices (vendor 0x05AC) via the hotplug broadcasts and UsbManager snapshots.
 */
class UsbMonitor(
    private val context: Context,
    private val logs: LogBus,
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<AppleUsbDevice>>(emptyList())
    val devices: StateFlow<List<AppleUsbDevice>> = _devices.asStateFlow()

    private var previousIds: Set<Int> = emptySet()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDeviceExtra()
                    logs.i(
                        "USB",
                        "检测到 USB 设备接入：${device?.productName ?: "未知"} (${device.vendorHex()})",
                    )
                    refresh()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDeviceExtra()
                    logs.i(
                        "USB",
                        "USB 设备已移除：${device?.productName ?: "未知"} (${device.vendorHex()})",
                    )
                    refresh()
                }

                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.usbDeviceExtra()
                    logs.i(
                        "USB",
                        "USB 权限${if (granted) "已授予" else "被拒绝"}：${device?.productName ?: ""}",
                    )
                    refresh()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    fun refresh() {
        val appleDevices = runCatching {
            usbManager.deviceList.values
                .filter { it.vendorId == APPLE_VENDOR_ID }
                .mapNotNull { device ->
                    runCatching {
                        AppleUsbDevice(
                            deviceId = device.deviceId,
                            vendorId = device.vendorId,
                            productId = device.productId,
                            productName = device.productName.orEmpty().ifBlank { "iPhone / iPad" },
                            hasPermission = usbManager.hasPermission(device),
                        )
                    }.getOrNull()
                }
                .sortedBy { it.deviceId }
        }.getOrElse {
            logs.w("USB", "枚举 USB 设备失败：${it.message}")
            emptyList()
        }

        val ids = appleDevices.map { it.deviceId }.toSet()
        if (ids != previousIds) {
            if (appleDevices.isEmpty() && previousIds.isNotEmpty()) {
                logs.i("USB", "Apple USB 设备已断开")
            } else if (appleDevices.isNotEmpty()) {
                logs.i("USB", "Apple USB 设备在线：${appleDevices.joinToString { it.idLabel }}")
            }
            previousIds = ids
        }
        _devices.value = appleDevices
    }

    fun requestPermission(device: AppleUsbDevice, allDevices: Collection<UsbDevice> = emptyList()) {
        val target = usbManager.deviceList.values.firstOrNull { it.deviceId == device.deviceId } ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        logs.i("USB", "请求 USB 权限：${device.productName}")
        usbManager.requestPermission(target, pending)
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun UsbDevice?.vendorHex(): String =
        this?.let { "%04X:%04X".format(it.vendorId, it.productId) } ?: "----:----"

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        const val APPLE_VENDOR_ID = 0x05AC
        private const val ACTION_USB_PERMISSION = "com.ibridge.otg.USB_PERMISSION"
    }
}
