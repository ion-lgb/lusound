package app.lusound

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.*
import app.lusound.library.LibraryViewModel
import app.lusound.ui.LuSoundApp
import java.util.concurrent.ExecutionException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val library: LibraryViewModel = viewModel()
            var controller by remember { mutableStateOf<MediaController?>(null) }
            val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED) }
            val requestAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                hasPermission = granted
                if (granted) library.startObserving() else library.reportError("未获得音乐读取权限；可以使用「导入文件」选择音频，或在系统设置中授权。")
            }
            val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) library.reportError("通知权限未授予；媒体会话通知适用系统豁免规则。")
            }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { library.importDocuments(it) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        hasPermission = ContextCompat.checkSelfPermission(this@MainActivity, audioPermission) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) library.startObserving()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            DisposableEffect(Unit) {
                val future = MediaController.Builder(this@MainActivity,
                    SessionToken(this@MainActivity, ComponentName(this@MainActivity, app.lusound.playback.PlaybackService::class.java))).buildAsync()
                future.addListener({
                    try { controller = future.get() }
                    catch (error: ExecutionException) { library.reportError("连接播放服务失败：${error.cause?.message}") }
                    catch (error: java.util.concurrent.CancellationException) { library.reportError("播放服务连接已取消：${error.message}") }
                    catch (error: InterruptedException) { Thread.currentThread().interrupt(); library.reportError("播放服务连接被中断") }
                }, ContextCompat.getMainExecutor(this@MainActivity))
                onDispose { MediaController.releaseFuture(future) }
            }
            LuSoundApp(library, controller, hasPermission,
                { requestAudio.launch(audioPermission) },
                { import.launch(arrayOf("audio/*", "application/octet-stream")) },
                {
                    if (Build.VERSION.SDK_INT >= 33) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                { controller?.let { openEqualizer(it, library) } })
        }
    }

    private fun openEqualizer(controller: MediaController, library: LibraryViewModel) {
        val future = controller.sendCustomCommand(SessionCommand(app.lusound.playback.AUDIO_SESSION_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
        future.addListener({
            try {
                val result = future.get()
                val id = result.extras.getInt("audioSessionId", 0)
                if (result.resultCode != SessionResult.RESULT_SUCCESS || id == 0) {
                    library.reportError("均衡器尚未就绪，请先播放一首歌曲。")
                } else {
                    startActivity(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC))
                }
            } catch (error: ActivityNotFoundException) {
                library.reportError("此设备未提供系统均衡器面板。")
            } catch (error: ExecutionException) {
                library.reportError("获取均衡器音频会话失败：${error.cause?.message}")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                library.reportError("获取均衡器音频会话被中断")
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
