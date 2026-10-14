package com.auradroid.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

class TaskSchedulerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        val folderPath = prefs.getString("folder_path", "/storage/emulated/0/Download/AutoVideos") ?: ""
        val command = prefs.getString("ai_command", "Upload next video with viral title and tags") ?: ""

        VideoQueueManager.appendLog(context, "⏰ Scheduled Automation Triggered!")

        val service = AutoTouchService.instance
        if (service != null) {
            service.startYouTubeAutoUpload(apiKey, model, folderPath, command)
        } else {
            VideoQueueManager.appendLog(context, "⚠️ Cannot auto-run: Enable Auto-Touch Service in Settings.")
        }
    }

    companion object {
        fun scheduleDailyUpload(context: Context, hour: Int, minute: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, TaskSchedulerReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        }
    }
}
