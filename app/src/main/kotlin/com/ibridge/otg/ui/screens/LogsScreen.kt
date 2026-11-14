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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ibridge.otg.core.AppController
import com.ibridge.otg.core.LogEntry
import com.ibridge.otg.core.LogLevel
import com.ibridge.otg.ui.components.EmptyState
import com.ibridge.otg.ui.components.MonoText
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LEVEL_TABS = listOf("全部", "详细", "信息", "警告", "错误")
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

@Composable
fun LogsScreen(controller: AppController) {
    val entries by controller.logs.entries.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var levelIndex by rememberSaveable { mutableIntStateOf(0) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val scrollState = rememberScrollState()

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) controller.saveLogs(uri)
    }

    val filtered = remember(entries, query, levelIndex) {
        val level = when (levelIndex) {
            1 -> LogLevel.VERBOSE
            2 -> LogLevel.INFO
            3 -> LogLevel.WARN
            4 -> LogLevel.ERROR
            else -> null
        }
        val q = query.trim().lowercase()
        entries.filter { entry ->
            (level == null || entry.level == level) &&
                (q.isEmpty() ||
                    entry.message.lowercase().contains(q) ||
                    entry.tag.lowercase().contains(q))
        }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp),
            insideMargin = PaddingValues(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索日志内容 / 标签",
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
                TextButton(
                    text = "保存",
                    onClick = {
                        val name = "ibridge-" + Instant.now()
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            .toString() + ".log"
                        saveLauncher.launch(name)
                    },
                )
                TextButton(text = "清空", onClick = { controller.clearLogs() })
            }
            Spacer(Modifier.height(8.dp))
            TabRow(
                tabs = LEVEL_TABS,
                selectedTabIndex = levelIndex,
                onTabSelected = { levelIndex = it },
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "共 ${filtered.size} 条 · 自动滚动",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
            }
        }

        if (filtered.isEmpty()) {
            EmptyState(
                icon = MiuixIcons.Notes,
                title = if (entries.isEmpty()) "暂无日志" else "没有匹配的日志",
                message = if (entries.isEmpty()) {
                    "连接设备、启动守护进程或执行操作后，这里会实时显示输出。"
                } else {
                    "调整搜索关键词或级别筛选后重试。"
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 110.dp),
            ) {
                items(filtered, key = { it.seq }) { entry ->
                    LogRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val levelColor = levelColor(entry.level)
    Column(modifier = Modifier.padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Instant.ofEpochMilli(entry.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(TIME_FORMATTER),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.level.label,
                style = MiuixTheme.textStyles.footnote2,
                color = levelColor,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.tag,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        MonoText(
            text = entry.message,
            fontSize = 12,
            color = if (entry.level == LogLevel.ERROR) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.VERBOSE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    LogLevel.INFO -> MiuixTheme.colorScheme.primary
    LogLevel.WARN -> Color(0xFFFF9F0A)
    LogLevel.ERROR -> MiuixTheme.colorScheme.error
}
