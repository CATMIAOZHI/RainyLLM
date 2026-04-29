package com.rainyllm.app

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import com.rainyllm.app.ui.navigation.Screen
import com.rainyllm.app.ui.screen.*
import com.rainyllm.app.ui.theme.RainyLLMTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论授权与否都不影响功能 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次启动请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        enableEdgeToEdge()
        setContent {
            RainyLLMTheme {
                MainScreen()
            }
        }
    }
}

/**
 * 隐藏时拦截所有触摸事件（点击+滑动+拖拽），不可见页面绝对不会响应用户操作。
 */
private fun Modifier.blockAllTouchWhenHidden(visible: Boolean): Modifier {
    if (visible) return this
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var currentTab by remember { mutableStateOf(Screen.Dashboard.route) }

    val screens = listOf(
        Screen.Dashboard,
        Screen.Models,
        Screen.Chat,
        Screen.Performance,
        Screen.Settings
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentTab == screen.route,
                        onClick = { currentTab = screen.route }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // 关键：可见页排在最后（Z-order最高），隐藏页排在前面
            // key(screen.route) 确保排序变化时不丢失 composable 身份和状态
            for (screen in screens.sortedBy { it.route == currentTab }) {
                val isVisible = screen.route == currentTab
                key(screen.route) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (isVisible) 1f else 0f }
                            .blockAllTouchWhenHidden(isVisible)
                    ) {
                        when (screen.route) {
                            Screen.Dashboard.route -> DashboardScreen()
                            Screen.Models.route -> ModelManagerScreen()
                            Screen.Chat.route -> ChatTestScreen()
                            Screen.Performance.route -> PerformanceScreen()
                            Screen.Settings.route -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RainyLLMAppPreview() {
    RainyLLMTheme {
        MainScreen()
    }
}