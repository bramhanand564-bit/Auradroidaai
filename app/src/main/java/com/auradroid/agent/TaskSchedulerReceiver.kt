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
        val flowUrl = VideoQueueManager.getPinnedFlowUrl(context)
        val flowTopic = VideoQueueManager.getFlowTopic(context)
        val dailyLimit = VideoQueueManager.getDailyVideoLimit(context)
        val autoFlowFirst = prefs.getBoolean("auto_flow_first", true)
        val hour = prefs.getInt("sched_hour", 19)
        val minute = prefs.getInt("sched_min", 0)

        VideoQueueManager.appendLog(context, "⏰ Scheduled Daily 7/8 PM Pipeline Triggered!")

        val service = AutoTouchService.instance
        if (service != null) {
            if (autoFlowFirst) {
                service.startGoogleFlowToYouTubePipeline(
                    apiKey = apiKey,
                    model = model,
                    flowUrl = flowUrl,
                    topicIdea = flowTopic,
                    dailyVideoCount = dailyLimit,
                    folderPath = folderPath
                )
            } else {
                service.startYouTubeAutoUpload(apiKey, model, folderPath, flowTopic)
            }
        } else {
            VideoQueueManager.appendLog(context, "⚠️ Cannot auto-run: Enable Auto-Touch Service in Settings.")
        }

        // Automatically schedule for the next day at the exact same time!
        scheduleDailyUpload(context, hour, minute)
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
