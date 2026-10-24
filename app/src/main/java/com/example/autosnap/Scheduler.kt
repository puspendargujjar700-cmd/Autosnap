package com.example.autosnap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object Scheduler {
    private fun alarmPi(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 1, Intent(c, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun showPi(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 2, Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!Config.enabled(c)) {
            am.cancel(alarmPi(c))
            return
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Config.hour(c))
            set(Calendar.MINUTE, Config.minute(c))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, showPi(c)), alarmPi(c))
    }
}
