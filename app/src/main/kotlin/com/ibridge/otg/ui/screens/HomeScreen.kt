package com.ibridge.otg.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ibridge.otg.core.AppController
import com.ibridge.otg.core.RootState
import com.ibridge.otg.daemon.DaemonState
import com.ibridge.otg.device.DevicePhase
import com.ibridge.otg.ui.components.ActionTile
import com.ibridge.otg.ui.components.InfoRow
import com.ibridge.otg.ui.components.PulseDot
import com.ibridge.otg.ui.components.SectionCard
import com.ibridge.otg.ui.components.StatusPill
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ConnectedGreen = Color(0xFF34C759)
private val WarningOrange = Color(0xFFFF9F0A)

@Composable
fun HomeScreen(
    controller: AppController,
    onNavigate: (Int) -> Unit,
) {
    val phase by controller.devicePhase.collectAsState()
    val daemonState by controller.daemonState.collectAsState()
    val rootState by controller.rootState.collectAsState()
    val tools by controller.tools.collectAsState()
    val usbDevices by controller.usbDevices.collectAsState()

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) controller.installFromUri(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("device") {
            DeviceHeroCard(
                phase = phase,
                daemonState = daemonState,
                rootState = rootState,
                usbDevices = usbDevices,
                onRefresh = { controller.refreshAll() },
                onPair = { controller.pair() },
                onStartDaemon = { controller.startDaemon() },
                onNavigateApps = { onNavigate(1) },
                onRequestPermission = {
                    usbDevices.firstOrNull { !it.hasPermission }?.let(controller::requestUsbPermission)
                },
            )
        }

        if (rootState !is RootState.Granted) {
            item("root") {
                RootWarningCard(
                    state = rootState,
                    onCheck = { controller.checkRoot() },
                )
            }
        }

        item("daemon") {
            DaemonCard(
                state = daemonState,
                onStart = { controller.startDaemon() },
                onStop = { controller.stopDaemon() },
                onRestart = { controller.restartDaemon() },
            )
        }

        item("tools") {
            ToolsSummaryCard(
                tools = tools,
                onOpenSettings = { onNavigate(4) },
            )
        }

        item("actions") {
            SectionCard(title = "快捷操作") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(
                        icon = MiuixIcons.UploadCloud,
                        title = "安装 IPA",
                        subtitle = "推送并安装到设备",
                        modifier = Modifier.weight(1f),
                        onClick = { installLauncher.launch(arrayOf("*/*")) },
                    )
                    ActionTile(
                        icon = MiuixIcons.ConvertFile,
                        title = "IPA 签名",
                        subtitle = "p12 + 描述文件",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(2) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(
                        icon = MiuixIcons.GridView,
                        title = "应用管理",
                        subtitle = "列表 · 卸载",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onNavigate(1)
                            controller.listApps()
                        },
                    )
                    ActionTile(
                        icon = MiuixIcons.Notes,
                        title = "运行日志",
                        subtitle = "搜索 · 保存",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(3) },
                    )
                }
            }
        }

        item("guide") {
            SectionCard(title = "使用流程") {
                GuideStep("1", "连接设备", "USB OTG 接入 iPhone，解锁并在弹窗中点击“信任”。")
                GuideStep("2", "配对与守护进程", "获取 Root 后自动拉起 usbmuxd，完成 lockdownd 配对。")
                GuideStep("3", "安装或签名", "直接推送 IPA；或使用 p12 与描述文件重签后再安装。")
            }
        }
    }
}

@Composable
private fun DeviceHeroCard(
    phase: DevicePhase,
    daemonState: DaemonState,
    rootState: RootState,
    usbDevices: List<com.ibridge.otg.usb.AppleUsbDevice>,
    onRefresh: () -> Unit,
    onPair: () -> Unit,
    onStartDaemon: () -> Unit,
    onNavigateApps: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val info = phase.infoOrNull
    val ready = phase is DevicePhase.Ready
    val paired = info?.paired == true

    Card(insideMargin = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MiuixTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Phone,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(27.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info?.displayName ?: "未连接 iPhone",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        info != null -> info.subtitle
                        usbDevices.isNotEmpty() -> "已检测到 Apple USB 设备"
                        else -> "使用 USB OTG 连接 iPhone"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            when {
                ready -> StatusPill(
                    "已配对",
                    ConnectedGreen.copy(alpha = 0.16f),
                    ConnectedGreen,
                )

                phase is DevicePhase.NotPaired -> StatusPill(
                    "未配对",
                    WarningOrange.copy(alpha = 0.16f),
                    WarningOrange,
                )

                phase is DevicePhase.Searching -> CircularProgressIndicator(
                    size = 20.dp,
                    modifier = Modifier.size(20.dp),
                )

                else -> PulseDot(
                    color = if (usbDevices.isNotEmpty()) WarningOrange else Color(0xFF8E8E93),
                    animated = usbDevices.isNotEmpty(),
                )
            }
        }

        if (info != null) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            InfoRow("设备型号", info.productType.ifBlank { "—" })
            InfoRow(
                "系统版本",
                if (info.productVersion.isBlank()) "—" else "iOS ${info.productVersion} (${info.buildVersion})",
            )
            InfoRow("序列号", info.serial.ifBlank { "—" })
            InfoRow("UDID", info.udid)
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                text = when (phase) {
                    DevicePhase.NoUsb -> "插入数据线后，应用会自动监听 USB 热插拔。"
                    DevicePhase.DaemonDown -> "设备已插入，但 usbmuxd 未运行。"
                    DevicePhase.Searching -> "正在等待设备响应，请保持解锁并确认信任弹窗。"
                    is DevicePhase.Error -> phase.message
                    else -> ""
                },
                style = MiuixTheme.textStyles.footnote1,
                color = when (phase) {
                    is DevicePhase.Error -> MiuixTheme.colorScheme.error
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.weight(1f),
            ) {
                Text("重新检测")
            }
            when {
                phase is DevicePhase.NotPaired -> Button(
                    onClick = onPair,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("配对设备")
                }

                ready -> Button(
                    onClick = onNavigateApps,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("应用管理")
                }

                phase is DevicePhase.DaemonDown || (usbDevices.isNotEmpty() && daemonState is DaemonState.Stopped) -> Button(
                    onClick = onStartDaemon,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("启动守护")
                }

                else -> Button(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("刷新")
                }
            }
        }

        if (usbDevices.any { !it.hasPermission } && rootState is RootState.Granted) {
            TextButton(
                text = "授予 USB 访问权限（可选，Root 守护进程不依赖此项）",
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RootWarningCard(
    state: RootState,
    onCheck: () -> Unit,
) {
    Card(insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(WarningOrange.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = WarningOrange,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "需要 Root 权限",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when (state) {
                        is RootState.Denied -> state.reason
                        else -> "usbmuxd 与原生工具链通过 su 执行，请先授予 Root。"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            TextButton(text = "检测", onClick = onCheck)
        }
    }
}

@Composable
private fun DaemonCard(
    state: DaemonState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val running = state is DaemonState.Running
    val color = when (state) {
        is DaemonState.Running -> ConnectedGreen
        is DaemonState.Starting -> WarningOrange
        is DaemonState.Failed -> MiuixTheme.colorScheme.error
        DaemonState.Stopped -> Color(0xFF8E8E93)
    }
    val title = when (state) {
        is DaemonState.Running -> "usbmuxd 运行中"
        is DaemonState.Starting -> "usbmuxd 正在启动"
        is DaemonState.Failed -> "usbmuxd 启动失败"
        DaemonState.Stopped -> "usbmuxd 未运行"
    }
    val detail = when (state) {
        is DaemonState.Running ->
            "127.0.0.1:${state.port}" + (state.pid?.let { " · pid $it" } ?: "")

        is DaemonState.Failed -> state.message
        is DaemonState.Starting -> "正在创建监听端口…"
        DaemonState.Stopped -> "启动后即可与 lockdownd 通信"
    }

    Card(insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(color = color, animated = state is DaemonState.Starting || running)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (state is DaemonState.Failed) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (state) {
                is DaemonState.Running -> Row {
                    TextButton(text = "重启", onClick = onRestart)
                    TextButton(text = "停止", onClick = onStop)
                }

                is DaemonState.Starting -> CircularProgressIndicator(size = 20.dp)
                else -> TextButton(text = "启动", onClick = onStart)
            }
        }
    }
}

@Composable
private fun ToolsSummaryCard(
    tools: List<com.ibridge.otg.tools.ToolStatus>,
    onOpenSettings: () -> Unit,
) {
    val ready = tools.count { it.installed }
    val missing = tools.filterNot { it.installed }
    Card(insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Tune,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "工具链 $ready/${tools.size}",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (missing.isEmpty()) {
                        "usbmuxd · idevice* · zsign 均已就绪"
                    } else {
                        "缺少：" + missing.joinToString("、") { it.id.fileName }
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (missing.isEmpty()) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.error
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(text = "管理", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun GuideStep(index: String, title: String, description: String) {
    Row(modifier = Modifier.padding(vertical = 7.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
