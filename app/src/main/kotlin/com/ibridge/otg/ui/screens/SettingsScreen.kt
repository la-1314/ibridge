package com.ibridge.otg.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import com.ibridge.otg.BuildConfig
import com.ibridge.otg.core.AppController
import com.ibridge.otg.core.RootState
import com.ibridge.otg.daemon.DaemonState
import com.ibridge.otg.tools.ToolStatus
import com.ibridge.otg.ui.components.ChoiceRow
import com.ibridge.otg.ui.components.InfoRow
import com.ibridge.otg.ui.components.SectionCard
import com.ibridge.otg.ui.components.StatusPill
import com.ibridge.otg.ui.theme.accentChoices
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun SettingsScreen(controller: AppController) {
    val themeMode by controller.prefs.themeMode.collectAsState()
    val accentIndex by controller.prefs.accentIndex.collectAsState()
    val autoStart by controller.prefs.autoStartDaemon.collectAsState()
    val stopOnExit by controller.prefs.stopDaemonOnExit.collectAsState()
    val rootState by controller.rootState.collectAsState()
    val daemonState by controller.daemonState.collectAsState()
    val tools by controller.tools.collectAsState()
    val port by controller.daemon.port.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) controller.importTools(uri)
    }

    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) controller.saveLogs(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("appearance") {
            SectionCard("外观") {
                ChoiceRow(
                    title = "跟随系统",
                    summary = "自动使用系统深浅色",
                    selected = themeMode == 0,
                    onClick = { controller.prefs.setThemeMode(0) },
                )
                ChoiceRow(
                    title = "浅色",
                    selected = themeMode == 1,
                    onClick = { controller.prefs.setThemeMode(1) },
                )
                ChoiceRow(
                    title = "深色",
                    selected = themeMode == 2,
                    onClick = { controller.prefs.setThemeMode(2) },
                )
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "主题色",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "基于 Miuix Monet 调色板生成强调色",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    accentChoices.forEachIndexed { index, choice ->
                        val selected = accentIndex == index
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(choice.swatch)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) {
                                        MiuixTheme.colorScheme.onSurface
                                    } else {
                                        MiuixTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { controller.prefs.setAccentIndex(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = MiuixIcons.Basic.Check,
                                    contentDescription = choice.name,
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item("root") {
            SectionCard("Root 与权限") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Root 权限",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = when (val s = rootState) {
                                RootState.Granted -> "已授权，原生工具通过 su 执行"
                                is RootState.Denied -> s.reason
                                else -> "尚未检测"
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = when (rootState) {
                                RootState.Granted -> Color(0xFF34C759)
                                is RootState.Denied -> MiuixTheme.colorScheme.error
                                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                    StatusPill(
                        text = when (rootState) {
                            RootState.Granted -> "已授权"
                            is RootState.Denied -> "未授权"
                            else -> "未知"
                        },
                        container = when (rootState) {
                            RootState.Granted -> Color(0xFF34C759).copy(alpha = 0.16f)
                            is RootState.Denied -> MiuixTheme.colorScheme.errorContainer
                            else -> MiuixTheme.colorScheme.surfaceVariant
                        },
                        content = when (rootState) {
                            RootState.Granted -> Color(0xFF34C759)
                            is RootState.Denied -> MiuixTheme.colorScheme.error
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { controller.checkRoot() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重新检测 Root")
                }
            }
        }

        item("daemon") {
            SectionCard("usbmuxd 守护进程") {
                InfoRow("状态", daemonStatusText(daemonState))
                InfoRow("监听端口", "127.0.0.1:$port")
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("连接设备时自动启动", style = MiuixTheme.textStyles.body1)
                        Text(
                            "USB 热插拔后自动拉起守护进程",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Switch(checked = autoStart, onCheckedChange = {
                        controller.prefs.setAutoStartDaemon(it)
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("退出时停止", style = MiuixTheme.textStyles.body1)
                        Text(
                            "应用任务结束时尝试关闭 usbmuxd",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Switch(checked = stopOnExit, onCheckedChange = {
                        controller.prefs.setStopDaemonOnExit(it)
                    })
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (daemonState is DaemonState.Running) {
                        Button(
                            onClick = { controller.restartDaemon() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("重启")
                        }
                        Button(
                            onClick = { controller.stopDaemon() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("停止")
                        }
                    } else {
                        Button(
                            onClick = { controller.startDaemon() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("启动")
                        }
                    }
                    Button(
                        onClick = { controller.reallocatePort() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("换端口")
                    }
                }
            }
        }

        item("tools") {
            SectionCard(
                title = "工具链（arm64-v8a）",
                titleAction = {
                    Text(
                        text = "${tools.count { it.installed }}/${tools.size}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
            ) {
                tools.forEachIndexed { index, tool ->
                    ToolRow(tool)
                    if (index != tools.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 34.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { controller.reExtractTools() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重新释放")
                    }
                    Button(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("导入工具包")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "将 arm64-v8a 静态二进制放入 app/src/main/assets/bin/arm64-v8a/ 后重新构建；" +
                        "也可以导入包含 usbmuxd、idevice*、zsign 的 zip 工具包。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item("storage") {
            SectionCard("日志与缓存") {
                Text(
                    text = "当前会话：${controller.logs.currentSessionFile.name}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            saveLogLauncher.launch("ibridge-logs-${System.currentTimeMillis()}.log")
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存日志")
                    }
                    Button(
                        onClick = { controller.clearLogs() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("清空日志")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { controller.clearCache() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清理输入 / 输出缓存")
                }
            }
        }

        item("about") {
            SectionCard("关于") {
                InfoRow("应用", "iBridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("ABI", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("签名", "zsign · libimobiledevice")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "仅用于管理本人拥有的 iOS 设备。请遵守 Apple 开发者协议与当地法律法规。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolStatus) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (tool.installed) {
                        Color(0xFF34C759).copy(alpha = 0.16f)
                    } else {
                        MiuixTheme.colorScheme.errorContainer
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (tool.installed) "✓" else "!",
                style = MiuixTheme.textStyles.footnote2,
                color = if (tool.installed) Color(0xFF34C759) else MiuixTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool.id.fileName,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = tool.id.description,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (tool.installed) formatSize(tool.sizeBytes) else "缺失",
            style = MiuixTheme.textStyles.footnote2,
            color = if (tool.installed) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.error
            },
        )
    }
}

private fun daemonStatusText(state: DaemonState): String = when (state) {
    is DaemonState.Running -> "运行中" + (state.pid?.let { " · pid $it" } ?: "")
    is DaemonState.Starting -> "启动中"
    is DaemonState.Failed -> "失败：${state.message}"
    DaemonState.Stopped -> "未运行"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
