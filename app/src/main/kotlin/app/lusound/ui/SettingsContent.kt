package app.lusound.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun SettingsContent(importFiles: () -> Unit, requestNotifications: () -> Unit, equalizer: () -> Unit, servers: app.lusound.cloud.ServersViewModel, removed: (String) -> Unit) {
    val links = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ServerSettings(servers, removed)
        HorizontalDivider()
        Text("音乐与播放", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(importFiles, Modifier.fillMaxWidth().testTag("settings_import")) { Text("导入音频文件") }
        OutlinedButton(equalizer, Modifier.fillMaxWidth().testTag("settings_eq")) { Text("打开系统均衡器") }
        if (Build.VERSION.SDK_INT >= 33) OutlinedButton(requestNotifications, Modifier.fillMaxWidth().testTag("settings_notifications")) { Text("管理通知授权") }
        Text("文件保留在原位置。系统不允许访问的其他应用私有目录无法扫描。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("外观", style = MaterialTheme.typography.titleLarge)
        Text("主题跟随系统 · Convx 玻璃材质")
        Text(when { Build.VERSION.SDK_INT >= 33 -> "此系统支持实时模糊与折射"; Build.VERSION.SDK_INT >= 31 -> "此系统支持实时模糊"; else -> "此系统使用半透明材质（Android 8–11）" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("关于琉声", style = MaterialTheme.typography.titleLarge)
        Text("LuSound 0.3.0 · 本地与私有云音乐")
        Text("UI Design Inspired by Convx\n包含 Convx 源码，遵循 GPL-3.0；玻璃渲染组件包含 Kyant0 的 Apache-2.0 源码。")
        TextButton({ links.openUri("https://github.com/cosmictaserdev-creator/Convx") }) { Text("查看 Convx 与许可证") }
        Text("Plex、Koel 原生 API 与加密音乐格式尚未接入。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(180.dp))
    }
}
