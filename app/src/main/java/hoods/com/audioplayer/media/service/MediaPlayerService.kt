package hoods.com.audioplayer.media.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import dagger.hilt.android.AndroidEntryPoint
import hoods.com.audioplayer.R
import hoods.com.audioplayer.media.constants.K
import hoods.com.audioplayer.media.exoplayer.MediaPlayerNotificationManager
import hoods.com.audioplayer.media.exoplayer.MediaSource
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class MediaPlayerService : MediaBrowserServiceCompat() {

    @Inject
    lateinit var dataSourceFactory: CacheDataSource.Factory

    @Inject
    lateinit var exoPlayer: ExoPlayer

    @Inject
    lateinit var mediaSource: MediaSource

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var mediaSession: MediaSessionCompat

    private lateinit var mediaSessionConnector: MediaSessionConnector

    private lateinit var mediaPlayerNotificationManager: MediaPlayerNotificationManager
    private var currentPlayingMedia: MediaMetadataCompat? = null
    private val isPlayerInitialized = false
    var isForegroundService: Boolean = false

    companion object {
        private const val TAG = "MediaPlayerService"

        var currentDuration: Long = 0L
            private set

    }

    override fun onCreate() {
        super.onCreate()
        val sessionActivityIntent = packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.let { sessionIntent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    sessionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_IMMUTABLE
                )


            }
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setSessionActivity(sessionActivityIntent)
            isActive = true
        }

        sessionToken = mediaSession.sessionToken

        mediaPlayerNotificationManager = MediaPlayerNotificationManager(
            this,
            mediaSession.sessionToken,
            PlayerNotificationListener()
        )

        serviceScope.launch {
            mediaSource.load()
        }

        mediaSessionConnector = MediaSessionConnector(mediaSession).apply {
            setPlaybackPreparer(AudioMediaPlayBackPreparer())
            setQueueNavigator(MediaQueueNavigator(mediaSession))
            setPlayer(exoPlayer)


        }
        mediaPlayerNotificationManager.showNotification(exoPlayer)

    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(K.MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {

        when (parentId) {
            K.MEDIA_ROOT_ID -> {

                val resultsSent = mediaSource.whenReady { isInitialized ->

                    if (isInitialized) {
                        result.sendResult(mediaSource.asMediaItem())
                    } else {
                        result.sendResult(null)
                    }

                }

                if (!resultsSent) {
                    result.detach()
                }


            }

            else -> Unit


        }


    }


    override fun onCustomAction(
        action: String,
        extras: Bundle?,
        result: Result<Bundle>
    ) {
        super.onCustomAction(action, extras, result)
        when (action) {

            K.START_MEDIA_PLAY_ACTION -> {
                mediaPlayerNotificationManager.showNotification(exoPlayer)
            }
            K.REFRESH_MEDIA_PLAY_ACTION -> {
                mediaSource.refresh()
                notifyChildrenChanged(K.MEDIA_ROOT_ID)
            }

            else -> Unit


        }


    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        exoPlayer.release()
    }


    inner class PlayerNotificationListener :
        PlayerNotificationManager.NotificationListener {
        override fun onNotificationCancelled(
            notificationId: Int,
            dismissedByUser: Boolean
        ) {
            stopForeground(true)
            isForegroundService = false
            stopSelf()
        }

        override fun onNotificationPosted(
            notificationId: Int,
            notification: Notification,
            ongoing: Boolean
        ) {
            if (ongoing && !isForegroundService) {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(
                        applicationContext,
                        this@MediaPlayerService.javaClass
                    )
                )
                startForeground(notificationId, notification)
                isForegroundService = true
            }
        }
    }

    inner class AudioMediaPlayBackPreparer :
        MediaSessionConnector.PlaybackPreparer {
        override fun onCommand(
            player: Player,
            command: String,
            extras: Bundle?,
            cb: ResultReceiver?
        ): Boolean {
            return false
        }

        override fun getSupportedPrepareActions(): Long =
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID

        override fun onPrepare(playWhenReady: Boolean) = Unit

        override fun onPrepareFromMediaId(
            mediaId: String,
            playWhenReady: Boolean,
            extras: Bundle?
        ) {
            mediaSource.whenReady {

                val itemToPlay = mediaSource.audioMediaMetaData.find {
                    it.description.mediaId == mediaId
                }

                currentPlayingMedia = itemToPlay

                preparePlayer(
                    mediaMetadata = mediaSource.audioMediaMetaData,
                    itemToPlay = itemToPlay,
                    playWhenReady = playWhenReady
                )


            }


        }

        override fun onPrepareFromSearch(
            query: String,
            playWhenReady: Boolean,
            extras: Bundle?
        ) = Unit

        override fun onPrepareFromUri(
            uri: Uri,
            playWhenReady: Boolean,
            extras: Bundle?
        ) = Unit
    }

    inner class MediaQueueNavigator(
        mediaSessionCompat: MediaSessionCompat
    ) : TimelineQueueNavigator(mediaSessionCompat) {
        override fun getMediaDescription(
            player: Player,
            windowIndex: Int
        ): MediaDescriptionCompat {

            if (windowIndex < mediaSource.audioMediaMetaData.size) {
                return mediaSource.audioMediaMetaData[windowIndex].description
            }

            return MediaDescriptionCompat.Builder().build()


        }
    }


    private fun preparePlayer(
        mediaMetadata: List<MediaMetadataCompat>,
        itemToPlay: MediaMetadataCompat?,
        playWhenReady: Boolean
    ) {
        val indexToPlay = if (currentPlayingMedia == null) 0
        else mediaMetadata.indexOf(itemToPlay)

        exoPlayer.addListener(PlayerEventListener())
        exoPlayer.setMediaSource(
            mediaSource
                .asMediaSource(dataSourceFactory)
        )
        exoPlayer.prepare()
        exoPlayer.seekTo(indexToPlay, 0)
        exoPlayer.playWhenReady = playWhenReady


    }


    private inner class PlayerEventListener : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {

            when (playbackState) {
                Player.STATE_BUFFERING,
                Player.STATE_READY -> {
                    mediaPlayerNotificationManager
                        .showNotification(exoPlayer)
                }
                else -> {
                    mediaPlayerNotificationManager
                        .hideNotification()
                }


            }


        }

        override fun onEvents(player: Player, events: Player.Events) {
            super.onEvents(player, events)
            currentDuration = player.duration
        }

        override fun onPlayerError(error: PlaybackException) {

            var message = R.string.generic_error

            if (error.errorCode ==
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            ) {
                message = R.string.error_media_not_found
            }

            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_SHORT
            ).show()


        }


    }


}