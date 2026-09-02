package com.v2ray.ang.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.v2ray.ang.receiver.MobileTinaExpiryReceiver
import java.util.concurrent.TimeUnit

object MobileTinaSessionLimiter {
    const val ACTION_SESSION_LIMIT = "com.v2ray.mobiletina.action.VPN_SESSION_LIMIT"

    private const val REQUEST_CODE = 24001
    private val MAX_SESSION_MILLIS = TimeUnit.HOURS.toMillis(24L)

    /**
     * Schedule the 24-hour VPN session cap without WorkManager.
     *
     * CoreVpnService runs in a dedicated process. Keeping this timer on AlarmManager avoids
     * cross-process WorkManager initialization/scheduler issues during VPN startup while
     * still using elapsed realtime so changing the device wall clock cannot extend a session.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = sessionLimitPendingIntent(app)
        val triggerAt = SystemClock.elapsedRealtime() + MAX_SESSION_MILLIS

        alarm.cancel(pendingIntent)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarm.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(sessionLimitPendingIntent(app))
    }

    private fun sessionLimitPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MobileTinaExpiryReceiver::class.java)
            .setAction(ACTION_SESSION_LIMIT)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
