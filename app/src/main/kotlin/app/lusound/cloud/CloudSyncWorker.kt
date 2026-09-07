package app.lusound.cloud

import android.content.Context
import android.util.Log
import androidx.work.*
import app.lusound.LuSoundApplication
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CloudSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LuSoundApplication
        var failed = false
        for (server in app.database.servers().all()) {
            try { app.cloud.sync(server.id) }
            catch (error: IOException) { failed = true; report(server.id, error.message.orEmpty()) }
            catch (error: SerializationException) { failed = true; report(server.id, "服务器响应格式无效，请检查协议兼容性") }
            catch (error: GeneralSecurityException) { failed = true; report(server.id, "无法解锁服务器凭据，请重新输入密码") }
        }
        return if (failed) Result.failure() else Result.success()
    }
    private suspend fun report(id: String, message: String) {
        (applicationContext as LuSoundApplication).database.servers().recordError(id, message)
        Log.w("LuSoundSync", buildJsonObject { put("event", "sync_failed"); put("serverId", id) }.toString())
    }
}

fun scheduleCloudSync(context: Context) {
    val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(6, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("subsonic-sync", ExistingPeriodicWorkPolicy.KEEP, request)
}
