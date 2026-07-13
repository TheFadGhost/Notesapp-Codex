package com.fadghost.notesapp.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fadghost.notesapp.MainActivity
import com.fadghost.notesapp.data.db.entity.Event
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Posts namespaced, source-safe calendar event alerts. */
object EventNotifier {
    private const val NOTIFICATION_TAG = "event"

    fun notify(context: Context, event: Event, occurrenceAt: Long): Boolean {
        if (!canNotify(context)) return false
        NotificationChannels.ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val zone = runCatching { ZoneId.of(event.timezone) }.getOrDefault(ZoneId.systemDefault())
        val starts = Instant.ofEpochMilli(occurrenceAt).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
        val notification = NotificationCompat.Builder(context, NotificationChannels.EVENTS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(event.title.ifBlank { "Event" })
            .setContentText("Starts $starts")
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setWhen(occurrenceAt)
            .setContentIntent(contentIntent(context, event.id))
            .build()
        return runCatching {
            manager.notify(NOTIFICATION_TAG, notificationId(event.id), notification)
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, eventId: Long) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_TAG, notificationId(eventId))
    }

    fun notificationId(eventId: Long): Int = stableId(eventId)

    private fun contentIntent(context: Context, eventId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_EVENT
            data = Uri.parse("${context.packageName}://notification/event/$eventId")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CALENDAR_EVENT_ID, eventId)
        }
        return PendingIntent.getActivity(
            context,
            stableId(eventId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun stableId(id: Long): Int = (id xor (id ushr 32)).toInt()

    private const val ACTION_OPEN_EVENT = "com.fadghost.notesapp.action.OPEN_EVENT"
    const val EXTRA_OPEN_CALENDAR_EVENT_ID = "open_calendar_event_id"
}
