package com.auradroid.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the next daily trigger after reboot. It only re-arms the alarm; it does not
 * start phone automation during boot, when foreground app state and user unlock are unknown.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(VideoQueueManager.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("daily_pipeline_enabled", false)) return

        val hour = prefs.getInt("schedule_hour", prefs.getInt("sched_hour", 19)).coerceIn(0, 23)
        val minute = prefs.getInt("schedule_minute", prefs.getInt("sched_min", 0)).coerceIn(0, 59)
        try {
            TaskSchedulerReceiver.scheduleDailyUpload(context, hour, minute)
            VideoQueueManager.appendLog(
                context,
                "🔄 Daily automation alarm restored after reboot; next run at ${String.format(java.util.Locale.US, "%02d:%02d", hour, minute)}."
            )
        } catch (e: Exception) {
            VideoQueueManager.appendLog(context, "⚠️ Could not restore daily schedule after reboot: ${e.message}")
        }
    }
}
