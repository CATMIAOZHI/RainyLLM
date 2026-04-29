package com.rainyllm.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * RainyLLM 底部导航路由定义
 */
sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Dashboard : Screen("dashboard", "主控台", Icons.Filled.Home)
    data object Models : Screen("models", "模型", Icons.Filled.CloudDownload)
    data object Chat : Screen("chat", "对话", Icons.AutoMirrored.Filled.Chat)
    data object Performance : Screen("performance", "性能", Icons.Filled.Memory)
    data object Settings : Screen("settings", "设置", Icons.Filled.Settings)
}
