package app.lusound.cloud

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lusound.LuSoundApplication
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

class ServersViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LuSoundApplication
    val servers = app.database.servers().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init { scheduleCloudSync(application) }

    fun save(draft: ServerDraft, completed: () -> Unit) { perform(completed) { app.cloud.save(draft) } }
    fun sync(id: String) { perform({}) { app.cloud.sync(id) } }
    fun remove(id: String, completed: () -> Unit) { perform(completed) { app.cloud.remove(id) } }
    fun clearError() { mutableError.value = null }

    private fun perform(completed: () -> Unit, operation: suspend () -> Unit) {
        if (mutableBusy.value) { mutableError.value = "请等待当前同步完成"; return }
        mutableBusy.value = true
        mutableError.value = null
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { operation() }; completed() }
            catch (error: IOException) { mutableError.value = error.message ?: "服务器连接失败，请检查地址与网络" }
            catch (error: SerializationException) { mutableError.value = "服务器响应格式无效，请检查所选协议与服务器版本" }
            catch (error: IllegalArgumentException) { mutableError.value = error.message ?: "服务器配置无效" }
            catch (error: GeneralSecurityException) { mutableError.value = "无法读取设备加密凭据，请重新输入服务器密码" }
            catch (error: SQLiteException) { mutableError.value = "保存服务器音乐库失败：${error.message}" }
            finally { mutableBusy.value = false }
        }
    }
}
