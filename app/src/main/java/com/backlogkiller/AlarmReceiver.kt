package com.backlogkiller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val slotId = intent.getStringExtra("slotId") ?: return
        val slot = Store.slots(ctx).find { it.id == slotId } ?: return
        val lec = Store.lectures(ctx).find { it.id == slot.lectureId } ?: return
        if (lec.done) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channelId = "live_class_v1"
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val channel = NotificationChannel(
            channelId, "Live Classes", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Live class start alerts"
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)

        val notifId = lec.id.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openIntent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPi = PendingIntent.getActivity(ctx, notifId, openIntent, flags)

        val joinIntent = Intent(ctx, MainActivity::class.java).apply {
            action = ACTION_JOIN
            putExtra("lectureId", lec.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val joinPi = PendingIntent.getActivity(ctx, notifId + 1, joinIntent, flags)

        val joinAction = Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_media_play),
            "Join Class",
            joinPi
        ).build()

        val n = Notification.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ðŸ”´ LIVE Class Started: ${lec.subject} - ${lec.name}")
            .setContentText("Tap Join Class to open PW (${lec.minutes} min)")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(joinAction)
            .build()

        nm.notify(notifId, n)
    }
}
