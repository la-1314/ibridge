package com.ibridge.otg.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ibridge.otg.core.AppController
import com.ibridge.otg.core.SignFileKind
import com.ibridge.otg.ui.components.FilePickRow
import com.ibridge.otg.ui.components.SectionCard
import com.ibridge.otg.ui.components.StatusPill
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun SignScreen(controller: AppController) {
    val inputs by controller.signInputs.collectAsState()
    val state by controller.signState.collectAsState()

    val ipaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) controller.selectSignFile(SignFileKind.IPA, uri) }

    val p12Picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) controller.selectSignFile(SignFileKind.P12, uri) }

    val provisionPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) controller.selectSignFile(SignFileKind.PROVISION, uri) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("files") {
            SectionCard("签名文件") {
                FilePickRow(
                    icon = MiuixIcons.File,
                    title = "IPA 安装包",
                    fileName = inputs.ipa?.name,
                    onClick = { ipaPicker.launch(arrayOf("*/*")) },
                )
                HorizontalDivider()
                FilePickRow(
                    icon = MiuixIcons.Lock,
                    title = "p12 证书",
                    fileName = inputs.p12?.name,
                    onClick = { p12Picker.launch(arrayOf("*/*")) },
                )
                HorizontalDivider()
                FilePickRow(
                    icon = MiuixIcons.Info,
                    title = "mobileprovision",
                    fileName = inputs.provision?.name,
                    onClick = { provisionPicker.launch(arrayOf("*/*")) },
                )
            }
        }

        item("config") {
            SectionCard("签名配置") {
                TextField(
                    value = inputs.password,
                    onValueChange = controller::updateSignPassword,
                    label = "p12 密码（无密码可留空）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = inputs.bundleId,
                    onValueChange = controller::updateBundleId,
                    label = "自定义 BundleID（可选，例如 com.example.app）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "留空则保持 IPA 原有 BundleID；自定义时需与描述文件中的 App ID 匹配。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item("action") {
            Button(
                onClick = { controller.sign() },
                modifier = Modifier.fillMaxWidth(),
                enabled = inputs.ready && !state.running,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(if (state.running) "正在签名…" else "开始签名")
            }
        }

        if (state.running || state.message.isNotBlank() || state.error != null || state.output != null) {
            item("state") {
                Card(insideMargin = PaddingValues(16.dp)) {
                    when {
                        state.error != null -> {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                StatusPill(
                                    "签名失败",
                                    MiuixTheme.colorScheme.errorContainer,
                                    MiuixTheme.colorScheme.error,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    text = "清除",
                                    onClick = { controller.removeSignOutput() },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.error ?: "",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }

                        state.output != null -> {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                StatusPill(
                                    "签名完成",
                                    Color(0xFF34C759).copy(alpha = 0.16f),
                                    Color(0xFF34C759),
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = formatSize(state.output!!.length()),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.output!!.name,
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { controller.installSigned() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                ) {
                                    Text("安装到设备")
                                }
                                Button(
                                    onClick = { controller.removeSignOutput() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("清除结果")
                                }
                            }
                        }

                        else -> {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Icon(
                                    imageVector = MiuixIcons.ConvertFile,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = if (state.progress != null) {
                                        "${(state.progress!! * 100).toInt()}%"
                                    } else {
                                        "处理中"
                                    },
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.message.ifBlank { "zsign 正在处理 IPA…" },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = state.progress,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        item("tips") {
            SectionCard("签名提示") {
                TipLine("zsign 会重写 Mach-O 签名、嵌入描述文件并重新打包 IPA。")
                TipLine("p12 密码错误、证书过期或描述文件不含设备 UDID 时，安装会失败。")
                TipLine("签名与安装均通过 Root 执行；签名结果保存在应用私有目录，可一键安装。")
                TipLine("修改 BundleID 后，请确认描述文件为通配符或已包含新 App ID。")
            }
        }
    }
}

@Composable
private fun TipLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "•",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.padding(start = 8.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
