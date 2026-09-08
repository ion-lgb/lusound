package app.lusound.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.core.net.toUri
import app.lusound.ncm.NcmRoutingDataSource
import app.lusound.MainActivity
import app.lusound.cloud.streamUrl
import app.lusound.cloud.PlexClient
import app.lusound.library.Track
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

const val AUDIO_SESSION_COMMAND: String = "app.lusound.AUDIO_SESSION"

/** Owns playback independently of the activity; Media3 manages foreground notification lifetime. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    override fun onCreate() {
        super.onCreate()
        val app = application as app.lusound.LuSoundApplication
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(app.http)
        val localAndHttp = androidx.media3.datasource.DefaultDataSource.Factory(this, http)
        val routed = androidx.media3.datasource.DataSource.Factory { NcmRoutingDataSource(this, localAndHttp) }
        val source = androidx.media3.datasource.ResolvingDataSource.Factory(routed) { spec ->
            if (spec.uri.scheme != "lusound") spec else {
                val serverId = requireNotNull(spec.uri.host) { "在线歌曲缺少服务器 ID" }
                val songId = requireNotNull(spec.uri.lastPathSegment) { "在线歌曲缺少歌曲 ID" }
                val server = app.database.servers().getForRequest(serverId) ?: throw java.io.IOException("服务器已移除")
                val url = if (server.kind == "PLEX") PlexClient(server, app.vault.decrypt(server.passwordCipher)).resolveStream(songId) else streamUrl(server, songId)
                spec.withUri(url.toUri())
            }
        }
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(source)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val bitmapLoader = androidx.media3.datasource.DataSourceBitmapLoader(
            androidx.media3.datasource.DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
            androidx.media3.datasource.DefaultDataSource.Factory(this, http))
        session = MediaSession.Builder(this, player).setBitmapLoader(CacheBitmapLoader(bitmapLoader)).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val base = super.onConnect(session, controller)
                if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                val commands = base.availableSessionCommands.buildUpon()
                if (controller.packageName == packageName) commands.add(SessionCommand(AUDIO_SESSION_COMMAND, Bundle.EMPTY))
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (command.customAction != AUDIO_SESSION_COMMAND) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putInt("audioSessionId", player.audioSessionId) }))
            }
        }).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onDestroy() {
        session.player.release()
        session.release()
        super.onDestroy()
    }
}

fun toMediaItem(track: Track): MediaItem = MediaItem.Builder()
    .setMediaId(track.uri).setUri(if (track.format == "ncm") app.lusound.ncm.ncmPlaybackUri(track.uri).toString() else track.uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
        .setAlbumTitle(track.album).setArtworkUri(track.artworkUri?.toUri()).build()).build()
