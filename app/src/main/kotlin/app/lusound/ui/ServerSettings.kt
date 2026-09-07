package app.lusound.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusound.cloud.*
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun ServerSettings(model: ServersViewModel, removed: (String) -> Unit) {
    val servers by model.servers.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ServerDraft?>(null) }
    var deleting by remember { mutableStateOf<Server?>(null) }
    Text("私有云音乐", style = MaterialTheme.typography.titleLarge)
    Text("Navidrome / Subsonic / Jellyfin · 联网时约每 6 小时同步一次，可手动刷新。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    servers.forEach { server ->
        OutlinedCard(Modifier.fillMaxWidth().testTag("server_${server.id}")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Text(server.baseUrl, style = MaterialTheme.typography.bodySmall)
                Text("最近同步：${if (server.lastSync == 0L) "尚未同步" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(server.lastSync))}", style = MaterialTheme.typography.bodySmall)
                server.syncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton({ model.sync(server.id) }, enabled = !busy, modifier = Modifier.testTag("sync_${server.id}")) { Text("同步") }
                    TextButton({ model.clearError(); editing = ServerDraft(server.id, server.name, server.baseUrl, server.username, "", server.kind) }, enabled = !busy) { Text("编辑") }
                    TextButton({ deleting = server }, enabled = !busy) { Text("移除") }
                }
            }
        }
    }
    OutlinedButton({ model.clearError(); editing = ServerDraft(UUID.randomUUID().toString(), "", "", "", "", "SUBSONIC") },
        enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("add_server")) { Text("添加音乐服务器") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("server_busy"))
    if (error != null && editing == null) Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
    editing?.let { draft ->
        val existing = servers.any { it.id == draft.id }
        AlertDialog(onDismissRequest = { if (!busy) editing = null }, title = { Text(if (existing) "更新连接" else "添加服务器") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!existing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("SUBSONIC" to "Subsonic", "JELLYFIN" to "Jellyfin").forEach { (kind, label) ->
                            FilterChip(selected = draft.kind == kind, onClick = { editing = draft.copy(kind = kind) },
                                enabled = !busy, label = { Text(label) }, modifier = Modifier.testTag("protocol_$kind"))
                        }
                    }
                    OutlinedTextField(draft.name, { editing = draft.copy(name = it) }, Modifier.testTag("server_name"), label = { Text("名称") }, singleLine = true, enabled = !busy)
                    OutlinedTextField(draft.baseUrl, { editing = draft.copy(baseUrl = it) }, Modifier.testTag("server_url"), label = { Text("服务器地址") }, placeholder = { Text("https://music.example.com/") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, enabled = !busy && !existing)
                    if (draft.baseUrl.trim().startsWith("http://")) Text("HTTP 不加密传输，请仅用于受信任的局域网。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.username, { editing = draft.copy(username = it) }, Modifier.testTag("server_username"), label = { Text("用户名") }, singleLine = true, enabled = !busy && !existing)
                    OutlinedTextField(draft.password, { editing = draft.copy(password = it) }, Modifier.testTag("server_password"), label = { Text(if (existing) "重新输入密码" else "密码") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !busy)
                    Text("歌曲与歌单同步到资料库。服务器歌单只读，可将在线歌曲加入本地歌单。", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, confirmButton = { TextButton({ model.save(draft) { editing = null } }, enabled = !busy, modifier = Modifier.testTag("save_server")) { Text("连接并同步") } },
            dismissButton = { TextButton({ editing = null }, enabled = !busy) { Text("取消") } })
    }
    deleting?.let { server ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("移除 ${server.name}？") },
            text = { Text("将移除此连接、缓存的在线歌曲和对应歌单关系；服务器上的音乐不会删除。") },
            confirmButton = { TextButton({ model.remove(server.id) { removed(server.id); deleting = null } }, enabled = !busy) { Text("移除") } },
            dismissButton = { TextButton({ deleting = null }, enabled = !busy) { Text("取消") } })
    }
}
