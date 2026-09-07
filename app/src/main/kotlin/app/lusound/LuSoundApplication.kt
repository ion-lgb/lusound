package app.lusound

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.lusound.cloud.*
import app.lusound.library.LibraryDatabase
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

/** Application-scoped connectors shared by the UI, playback service and WorkManager. */
class LuSoundApplication : Application(), SingletonImageLoader.Factory {
    val database: LibraryDatabase by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "lusound.db").addMigrations(SERVER_MIGRATION, JELLYFIN_MIGRATION).build()
    }
    val vault: CredentialVault by lazy { CredentialVault() }
    val cloud: CloudRepository by lazy { CloudRepository(database, vault) }
    val http: okhttp3.OkHttpClient by lazy {
        baseHttpClient().newBuilder().addInterceptor(SavedServerInterceptor(database.servers(), vault)).build()
    }
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }.build()
}
