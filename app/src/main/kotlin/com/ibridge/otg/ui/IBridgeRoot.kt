package com.ibridge.otg.ui

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.ibridge.otg.core.AppController
import com.ibridge.otg.ui.screens.AppsScreen
import com.ibridge.otg.ui.screens.HomeScreen
import com.ibridge.otg.ui.screens.LogsScreen
import com.ibridge.otg.ui.screens.SettingsScreen
import com.ibridge.otg.ui.screens.SignScreen
import com.ibridge.otg.ui.theme.IBridgeTheme
import com.ibridge.otg.ui.theme.resolveDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.ibridge.otg.ui.components.BusyBar

private data class TabSpec(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun IBridgeRoot(controller: AppController) {
    val themeMode by controller.prefs.themeMode.collectAsState()
    val accentIndex by controller.prefs.accentIndex.collectAsState()

    IBridgeTheme(themeMode = themeMode, accentIndex = accentIndex) {
        val dark = resolveDarkTheme(themeMode)
        ApplySystemBars(dark)

        val tabs = remember {
            listOf(
                TabSpec("设备", "USB · lockdownd", MiuixIcons.Home),
                TabSpec("应用", "安装 · 卸载 · 列表", MiuixIcons.GridView),
                TabSpec("签名", "zsign · p12", MiuixIcons.ConvertFile),
                TabSpec("日志", "实时输出与检索", MiuixIcons.Notes),
                TabSpec("设置", "工具链 · 外观", MiuixIcons.Settings),
            )
        }

        var tab by rememberSaveable { mutableIntStateOf(0) }
        val snackbarHostState = remember { SnackbarHostState() }
        val operation by controller.operation.collectAsState()

        val contentAlpha = remember { Animatable(0.4f) }
        LaunchedEffect(tab) {
            contentAlpha.snapTo(0.4f)
            contentAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            )
        }

        LaunchedEffect(controller) {
            controller.messages.collect { message ->
                snackbarHostState.showSnackbar(message.text)
            }
        }

        Scaffold(
            containerColor = MiuixTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = tabs[tab].title,
                    subtitle = tabs[tab].subtitle,
                    actions = {
                        IconButton(
                            onClick = {
                                controller.prefs.setThemeMode(if (dark) 1 else 2)
                            },
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Theme,
                                contentDescription = "切换明暗主题",
                            )
                        }
                        IconButton(onClick = { controller.refreshAll() }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "刷新",
                            )
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, spec ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = spec.icon,
                            label = spec.title,
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MiuixTheme.colorScheme.background),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentAlpha.value
                            translationY = (1f - contentAlpha.value) * 14f
                        },
                ) {
                    when (tab) {
                        0 -> HomeScreen(
                            controller = controller,
                            onNavigate = { tab = it },
                        )

                        1 -> AppsScreen(controller = controller, onNavigateHome = { tab = 0 })
                        2 -> SignScreen(controller = controller)
                        3 -> LogsScreen(controller = controller)
                        else -> SettingsScreen(controller = controller)
                    }
                }

                if (operation != null) {
                    val op = operation!!
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        BusyBar(
                            title = op.title,
                            detail = op.detail,
                            progress = op.progress,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplySystemBars(dark: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(dark, view) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
