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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ibridge.otg.core.AppController
import com.ibridge.otg.device.DevicePhase
import com.ibridge.otg.device.IosAppInfo
import com.ibridge.otg.ui.components.EmptyState
import com.ibridge.otg.ui.components.SectionCard
import com.ibridge.otg.ui.components.StatusPill
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.Color

@Composable
fun AppsScreen(
    controller: AppController,
    onNavigateHome: () -> Unit,
) {
    val apps by controller.apps.collectAsState()
    val phase by controller.devicePhase.collectAsState()
    val listing by controller.listingApps.collectAsState()

    var query by remember { mutableStateOf("") }
    var pendingUninstall by remember { mutableStateOf<IosAppInfo?>(null) }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) controller.installFromUri(uri)
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter {
            it.name.lowercase().contains(q) || it.bundleId.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("toolbar") {
            Card(insideMargin = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "搜索应用或 BundleID",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            Icon(
                                imageVector = MiuixIcons.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { controller.listApps() }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "刷新应用列表",
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { controller.listApps() },
                        modifier = Modifier.weight(1f),
                        enabled = phase is DevicePhase.Ready && !listing,
                    ) {
                        Text(if (listing) "读取中…" else "读取应用列表")
                    }
                    Button(
                        onClick = { installLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = phase is DevicePhase.Ready,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("安装 IPA")
                    }
                }
                if (listing) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (phase !is DevicePhase.Ready) {
            item("not-ready") {
                Card(insideMargin = PaddingValues(16.dp)) {
                    EmptyState(
                        icon = MiuixIcons.GridView,
                        title = "设备未就绪",
                        message = when (phase) {
                            DevicePhase.NoUsb -> "请先通过 USB OTG 连接 iPhone。"
                            DevicePhase.DaemonDown -> "usbmuxd 未运行，无法与设备通信。"
                            DevicePhase.Searching -> "正在等待设备响应，请解锁并点击“信任”。"
                            is DevicePhase.NotPaired -> "设备尚未配对，请先在设备页完成配对。"
                            is DevicePhase.Error -> (phase as DevicePhase.Error).message
                            else -> "请稍后重试。"
                        },
                    ) {
                        TextButton(text = "返回设备页", onClick = onNavigateHome)
                    }
                }
            }
        } else if (filtered.isEmpty()) {
            item("empty") {
                Card(insideMargin = PaddingValues(16.dp)) {
                    EmptyState(
                        icon = MiuixIcons.GridView,
                        title = if (query.isBlank()) "暂无应用" else "没有匹配的应用",
                        message = if (query.isBlank()) {
                            "点击“读取应用列表”从设备获取已安装应用。"
                        } else {
                            "换个关键词试试，支持按名称或 BundleID 搜索。"
                        },
                    ) {
                        if (query.isBlank()) {
                            TextButton(text = "读取列表", onClick = { controller.listApps() })
                        }
                    }
                }
            }
        } else {
            item("count") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已安装 ${filtered.size} 个应用",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (query.isNotBlank()) {
                        StatusPill(
                            text = "筛选：$query",
                            container = MiuixTheme.colorScheme.primaryContainer,
                            content = MiuixTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            items(filtered, key = { it.bundleId }) { app ->
                AppRow(
                    app = app,
                    onUninstall = { pendingUninstall = app },
                )
            }
        }
    }

    if (pendingUninstall != null) {
        val app = pendingUninstall!!
        OverlayDialog(
            show = true,
            title = "卸载应用",
            summary = "确定从设备卸载「${app.name}」？\n${app.bundleId}",
            onDismissRequest = { pendingUninstall = null },
            content = {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = "取消",
                        onClick = { pendingUninstall = null },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "卸载",
                        onClick = {
                            controller.uninstall(app.bundleId)
                            pendingUninstall = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        )
    }
}

@Composable
private fun AppRow(
    app: IosAppInfo,
    onUninstall: () -> Unit,
) {
    Card(insideMargin = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MiuixTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = app.initial,
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.version.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = app.version,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Text(
                    text = app.bundleId,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onUninstall) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "卸载 ${app.name}",
                    tint = Color(0xFFFF3B30),
                )
            }
        }
    }
}
