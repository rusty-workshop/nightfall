package com.rusty.nightfall.drawer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.wear.tiles.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NotifItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val clearable: Boolean,
    val open: PendingIntent?,
)

data class NowPlaying(val title: String, val artist: String, val playing: Boolean)

/**
 * Notification access: feeds the Nightfall inbox, lets the control panel switch Do Not Disturb
 * (listeners may set the interruption filter without extra permissions), and finds the active
 * media session for the Music tile.
 */
class NotifListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val controllers = mutableListOf<MediaController>()
    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it ?: emptyList()) }

    override fun onListenerConnected() {
        instance = this
        publish()
        runCatching {
            val msm = getSystemService(MediaSessionManager::class.java)
            val me = ComponentName(this, NotifListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, main)
            watch(msm.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = publish()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = publish()

    private fun publish() {
        val pm = packageManager
        val list = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && it.packageName != packageName }
            .mapNotNull { sbn ->
                val e = sbn.notification.extras
                val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                NotifItem(sbn.key, sbn.packageName, app, title, text, sbn.postTime, sbn.isClearable, sbn.notification.contentIntent)
            }
            .sortedByDescending { it.time }
        _items.value = list
    }

    private fun watch(sessions: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers.clear()
        sessions.forEach { it.registerCallback(mediaCallback, main); controllers += it }
        publishMedia()
    }

    private var lastTileUpdate = 0L
    private fun publishMedia() {
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        val md = c?.metadata
        _media.value = if (c == null) null else NowPlaying(
            md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
        val now = System.currentTimeMillis()
        if (now - lastTileUpdate > 1500) {   // a burst of callbacks (track change) = one tile refresh
            lastTileUpdate = now
            runCatching { TileService.getUpdater(this).requestUpdate(MusicTileService::class.java) }
        }
    }

    companion object {
        @Volatile var instance: NotifListener? = null
            private set
        private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
        val items: StateFlow<List<NotifItem>> = _items
        private val _media = MutableStateFlow<NowPlaying?>(null)
        val media: StateFlow<NowPlaying?> = _media

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { it.startsWith(ctx.packageName + "/") }
        }

        fun dismiss(key: String) { runCatching { instance?.cancelNotification(key) } }
        fun dismissAll() { runCatching { instance?.cancelAllNotifications() } }

        fun dndOn(ctx: Context): Boolean =
            ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        /** Returns false when notification access isn't granted (the panel then shows how to set it up). */
        fun setDnd(on: Boolean): Boolean {
            val l = instance ?: return false
            l.requestInterruptionFilter(if (on) INTERRUPTION_FILTER_PRIORITY else INTERRUPTION_FILTER_ALL)
            return true
        }

        /** The active media session's controls (Music tile buttons). */
        fun media(action: String) {
            val c = instance?.controllers?.let { cs -> cs.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: cs.firstOrNull() } ?: return
            val t = c.transportControls
            when (action) {
                "pp" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                "next" -> t.skipToNext()
                "prev" -> t.skipToPrevious()
            }
        }
    }
}
