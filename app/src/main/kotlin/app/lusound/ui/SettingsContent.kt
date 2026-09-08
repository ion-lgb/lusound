package app.lusound.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
fun SettingsContent(importFiles: () -> Unit, requestNotifications: () -> Unit, equalizer: () -> Unit, servers: app.lusound.cloud.ServersViewModel, library: app.lusound.library.LibraryViewModel, importFolder: () -> Unit, folderRemoved: (String) -> Unit, removed: (String) -> Unit) {
    val links = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup { ServerSettings(servers, removed) }
        SettingsGroup {
            Text("音乐与播放", style = MaterialTheme.typography.titleMedium)
            SettingsAction(Icons.Rounded.AudioFile, "导入音频文件", "settings_import", importFiles)
            FolderSettings(library, importFolder, folderRemoved)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsAction(Icons.Rounded.Equalizer, "系统均衡器", "settings_eq", equalizer)
            if (Build.VERSION.SDK_INT >= 33) SettingsAction(Icons.Rounded.NotificationsNone, "通知授权", "settings_notifications", requestNotifications)
        }
        MetadataSettings()
        SettingsGroup {
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Text("跟随系统 · 玻璃材质")
            Text(when { Build.VERSION.SDK_INT >= 33 -> "实时模糊与折射"; Build.VERSION.SDK_INT >= 31 -> "实时模糊"; else -> "半透明材质 · Android 8–11" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup {
            Text("关于琉声", style = MaterialTheme.typography.titleMedium)
            Text("LuSound ${app.lusound.BuildConfig.VERSION_NAME} · 本地与私有云音乐")
            Text("UI Design Inspired by Convx\n包含 Convx 源码，遵循 GPL-3.0；玻璃渲染组件包含 Kyant0 的 Apache-2.0 源码。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ links.openUri("https://github.com/cosmictaserdev-creator/Convx") }) { Text("查看 Convx 与许可证") }
            Text("NCM 支持本地内存解密播放；QMC、KGM 与 Koel 原生 API 尚未接入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(180.dp))
    }
}
