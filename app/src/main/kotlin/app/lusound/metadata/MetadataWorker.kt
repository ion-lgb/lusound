package app.lusound.metadata

import android.content.Context
import android.util.Log
import androidx.work.*
import app.lusound.LuSoundApplication
import java.io.IOException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun metadataEnabled(context: Context): Boolean = context.getSharedPreferences("metadata", Context.MODE_PRIVATE).getBoolean("enabled", true)

fun setMetadataEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences("metadata", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    if (enabled) scheduleMetadata(context)
    else WorkManager.getInstance(context).cancelUniqueWork("music-metadata")
}

fun scheduleMetadata(context: Context) {
    if (!metadataEnabled(context)) return
    val request = OneTimeWorkRequestBuilder<MetadataWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    WorkManager.getInstance(context).enqueueUniqueWork("music-metadata", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
}

class MetadataWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LuSoundApplication
        var failed = false
        for (track in app.database.library().getTracks()) {
            if (!metadataEnabled(app)) break
            try { app.metadata.enrich(track) }
            catch (error: IOException) {
                failed = true
                app.metadata.recordFailure(track, error.message.orEmpty())
                Log.w("LuSoundMetadata", buildJsonObject { put("event", "lookup_failed"); put("reason", error.message) }.toString())
            } catch (error: SecurityException) {
                failed = true
                app.metadata.recordFailure(track, error.message.orEmpty())
                Log.w("LuSoundMetadata", buildJsonObject { put("event", "permission_expired"); put("reason", error.message) }.toString())
            }
        }
        return if (failed) Result.failure() else Result.success()
    }
}
