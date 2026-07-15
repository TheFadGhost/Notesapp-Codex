package com.fadghost.notesapp.notify

import android.Manifest
import android.app.Notification
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
import com.fadghost.notesapp.alarm.ReminderActionReceiver
import com.fadghost.notesapp.data.db.entity.Reminder

/** Posts reminder notifications with durable actions and a source-note-aware content tap. */
object ReminderNotifier {
    private const val LANE_CONTENT = 0
    private const val LANE_DONE = 1
    private const val LANE_SNOOZE_10 = 2
    private const val LANE_SNOOZE_60 = 3
    private const val LANES = 4
    private const val NOTIFICATION_TAG = "reminder"

    /** Returns true only when the notification was actually handed to the system. */
    fun notify(context: Context, reminder: Reminder, liveSourceNoteId: Long? = null): Boolean {
        if (!canNotify(context)) return false
        NotificationChannels.ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val id = reminder.id
        val notification: Notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.title.ifBlank { "Reminder" })
            .setContentText("Tap to open")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, reminder, liveSourceNoteId))
            .addAction(0, "Done", actionIntent(context, id, ReminderActionReceiver.ACTION_DONE, LANE_DONE))
            .addAction(0, "Snooze 10m", actionIntent(context, id, ReminderActionReceiver.ACTION_SNOOZE_10, LANE_SNOOZE_10))
            .addAction(0, "Snooze 1h", actionIntent(context, id, ReminderActionReceiver.ACTION_SNOOZE_60, LANE_SNOOZE_60))
            .build()
        return runCatching {
            manager.notify(NOTIFICATION_TAG, notificationId(id), notification)
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, reminderId: Long) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_TAG, notificationId(reminderId))
    }

    fun notificationId(reminderId: Long): Int = stableId(reminderId)

    fun canNotify(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            manager.getNotificationChannel(NotificationChannels.REMINDERS)?.importance !=
            NotificationManager.IMPORTANCE_NONE
    }

    private fun requestCode(reminderId: Long, lane: Int): Int =
        (stableId(reminderId) * LANES) + lane

    private fun contentIntent(
        context: Context,
        reminder: Reminder,
        liveSourceNoteId: Long?
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_REMINDER
            data = Uri.parse("${context.packageName}://notification/reminder/${reminder.id}")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            if (liveSourceNoteId != null) putExtra(EXTRA_OPEN_NOTE_ID, liveSourceNoteId)
            else putExtra(EXTRA_OPEN_CALENDAR, true)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(reminder.id, LANE_CONTENT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(context: Context, reminderId: Long, action: String, lane: Int): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("${context.packageName}://notification/reminder/$reminderId/action/$lane")
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(reminderId, lane),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stableId(id: Long): Int = (id xor (id ushr 32)).toInt()

    private const val ACTION_OPEN_REMINDER = "com.fadghost.notesapp.action.OPEN_REMINDER"
    const val EXTRA_OPEN_CALENDAR = "open_calendar"
    const val EXTRA_OPEN_NOTE_ID = "open_note_id"
    const val EXTRA_REMINDER_ID = "reminder_id"
}
