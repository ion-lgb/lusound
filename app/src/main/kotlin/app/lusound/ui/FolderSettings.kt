package app.lusound.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusound.library.LibraryViewModel

@Composable
fun FolderSettings(library: LibraryViewModel, importFolder: () -> Unit, removed: (String) -> Unit) {
    val folders by library.folders.collectAsStateWithLifecycle()
    val scanning by library.folderScanning.collectAsStateWithLifecycle()
    val status by library.folderStatus.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<String?>(null) }
    FilledTonalButton(importFolder, Modifier.fillMaxWidth().testTag("settings_import_folder"), enabled = !scanning) { Text("添加音乐文件夹") }
    Text("扫描目录及子目录，文件保留在原位置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("folder_scanning"))
    status?.let { Text(it, Modifier.testTag("folder_status"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    folders.forEach { folder ->
        Card(Modifier.fillMaxWidth().testTag("folder_$folder")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(DocumentsContract.getTreeDocumentId(Uri.parse(folder)), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ library.rescanFolder(folder) }, Modifier.testTag("rescan_$folder"), enabled = !scanning) { Text("重新扫描") }
                    TextButton({ removing = folder }, Modifier.testTag("remove_$folder"), enabled = !scanning) { Text("移除") }
                }
            }
        }
    }
    removing?.let { folder ->
        MusicSheet(canDismiss = { true }, onDismissRequest = { removing = null }, title = { Text("移除音乐文件夹？") },
            text = { Text("将移除此目录的授权、歌曲映射及其歌单条目，并从当前队列移除。原文件不会删除。") },
            confirmButton = { TextButton({ library.removeFolder(folder) { removed(folder) }; removing = null }, Modifier.testTag("confirm_remove_folder")) { Text("移除") } },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } })
    }
}
