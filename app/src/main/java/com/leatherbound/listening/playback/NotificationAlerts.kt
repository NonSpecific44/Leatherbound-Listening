package com.leatherbound.listening.playback

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow

/** Tells [PlaybackService] a notification just alerted. */
object NotificationAlerts {
    val posted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private const val QUIET_FLAGS = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or
        Notification.FLAG_ONLY_ALERT_ONCE or Notification.FLAG_GROUP_SUMMARY

    /** True for a notification that makes a sound: not ongoing, not an update, and allowed through Do Not Disturb. */
    fun alerts(importance: Int, channelHasSound: Boolean, matchesFilter: Boolean, flags: Int): Boolean =
        importance >= NotificationManager.IMPORTANCE_DEFAULT && channelHasSound && matchesFilter && flags and QUIET_FLAGS == 0
}

/**
 * Optional (needs "Notification access"): hears about notifications even when their sound doesn't
 * play on the phone, as in Android Auto. It looks only at whether one alerts, never at its content.
 */
class AlertListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (sbn.packageName == packageName) return
        val ranking = Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return
        val alerts = NotificationAlerts.alerts(
            ranking.importance, ranking.channel?.sound != null, ranking.matchesInterruptionFilter(), sbn.notification.flags,
        )
        if (alerts) NotificationAlerts.posted.tryEmit(Unit)
    }
}
