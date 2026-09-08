package app.lusound.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.lusound.metadata.metadataEnabled
import app.lusound.metadata.setMetadataEnabled

@Composable
fun MetadataSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(metadataEnabled(context)) }
    SettingsGroup {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动搜索歌词与封面", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(enabled, { enabled = it; setMetadataEnabled(context, it) }, Modifier.testTag("auto_metadata"))
        }
        Text("优先使用文件内嵌内容，联网补齐缺失项并缓存。向 LRCLIB、MusicBrainz / Cover Art Archive 发送歌名、歌手、专辑及匹配所需的时长，不上传音频文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
