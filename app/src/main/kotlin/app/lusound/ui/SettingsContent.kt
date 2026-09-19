/**
 * Adapted from Convx SettingsScreen.kt (Convx Project (C) 2026, GPL-3.0).
 * Groups expose LuSound's existing features; see NOTICE for source attribution.
 */
package app.lusound.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.convx.music.ui.component.floatingtabbar.FloatingTabBarScrollConnection

/**
 * Reports rows the library is hiding because another row is the same file, and cleans them up only
 * when the user asks.
 *
 * Nothing here happens automatically: hiding is reversible and lossless, while deleting is neither,
 * so the row appears only when there is something to explain and the deletion waits for a confirmation.
 */
@Composable
private fun DuplicateCleanup(library: app.lusound.library.LibraryViewModel) {
    val duplicates by library.duplicateCount.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    if (duplicates <= 0) return
    ConvxSettingsDivider()
    SettingsAction(Icons.Rounded.ContentCopy, "清理 $duplicates 条重复记录", "settings_duplicates") { confirming = true }
    if (confirming) {
        MusicSheet(canDismiss = { true }, onDismissRequest = { confirming = false }, title = { Text("清理重复记录？") },
            text = {
                Text(
                    "同一文件经不同来源导入时会留下多条记录，资料库目前只显示其中一条。" +
                        "清理会删除被隐藏的记录，并把歌单里的引用改指向保留的那条；原文件不会被删除。",
                    Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                val complete = LocalMusicSheetComplete.current
                TextButton({ library.removeDuplicateRows(); complete { confirming = false } }, Modifier.testTag("confirm_duplicates")) { Text("清理") }
            },
            dismissButton = {
                val complete = LocalMusicSheetComplete.current
                TextButton({ complete { confirming = false } }, Modifier.testTag("cancel_duplicates")) { Text("取消") }
            })
    }
}

@Composable
fun SettingsContent(importFiles: () -> Unit, requestNotifications: () -> Unit, equalizer: () -> Unit, servers: app.lusound.cloud.ServersViewModel, library: app.lusound.library.LibraryViewModel, importFolder: () -> Unit, folderRemoved: (String) -> Unit, removed: (String) -> Unit, back: () -> Unit, scroll: FloatingTabBarScrollConnection) {
    val links = LocalUriHandler.current
    val list = rememberScrollState()
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    val collapsed by remember(list, threshold) { derivedStateOf { (list.value / threshold).coerceIn(0f, 1f) } }
    Box(Modifier.fillMaxSize().nestedScroll(scroll)) {
        Column(Modifier.fillMaxSize().verticalScroll(list).testTag("settings_list").padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("设置", Modifier.weight(1f).testTag("settings_large_title"), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 41.sp))
                Spacer(Modifier.size(48.dp))
            }
            ConvxSettingsHeader("私有云音乐")
            SettingsGroup { ServerSettings(servers, removed) }
            Spacer(Modifier.height(20.dp))
            ConvxSettingsHeader("音乐与播放")
            SettingsGroup {
                SettingsAction(Icons.Rounded.AudioFile, "导入音频文件", "settings_import", importFiles)
                ConvxSettingsDivider()
                FolderSettings(library, importFolder, folderRemoved)
                DuplicateCleanup(library)
                ConvxSettingsDivider()
                SettingsAction(Icons.Rounded.Equalizer, "系统均衡器", "settings_eq", equalizer)
                if (Build.VERSION.SDK_INT >= 33) {
                    ConvxSettingsDivider()
                    SettingsAction(Icons.Rounded.NotificationsNone, "通知授权", "settings_notifications", requestNotifications)
                }
            }
            Spacer(Modifier.height(20.dp))
            ConvxSettingsHeader("歌词与封面")
            MetadataSettings()
            Spacer(Modifier.height(20.dp))
            ConvxSettingsHeader("外观")
            SettingsGroup {
                ConvxSettingsBody {
                    Text("跟随系统 · Convx 默认界面", style = MaterialTheme.typography.bodyLarge)
                    Text(when { Build.VERSION.SDK_INT >= 33 -> "实时模糊与折射"; Build.VERSION.SDK_INT >= 31 -> "实时模糊"; else -> "半透明材质 · Android 8–11" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            ConvxSettingsHeader("关于琉声")
            SettingsGroup {
                ConvxSettingsBody {
                    Text("LuSound ${app.lusound.BuildConfig.VERSION_NAME} · 本地与私有云音乐")
                    Text("UI Design Inspired by Convx\n包含 Convx 源码，遵循 GPL-3.0；玻璃渲染组件包含 Kyant0 的 Apache-2.0 源码。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("NCM 支持本地内存解密播放；QMC、KGM 与 Koel 原生 API 尚未接入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConvxSettingsDivider()
                SettingsAction(Icons.Rounded.Info, "查看 Convx 与许可证", "settings_license") {
                    links.openUri("https://github.com/cosmictaserdev-creator/Convx")
                }
            }
            Spacer(Modifier.height(180.dp))
        }
        // A single back control keeps the existing test/accessibility identity throughout collapse.
        Row(Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f * collapsed))
            .statusBarsPadding().padding(top = (24f * (1f - collapsed)).dp)
            .height(52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("设置", Modifier.weight(1f).testTag("settings_compact_title").graphicsLayer { alpha = collapsed },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回资料库", "settings", back)
        }
    }
}
