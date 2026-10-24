package com.example.autosnap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.schedule(c) // kal ke liye dobara set
        val s = AutoSnapService.instance
        if (s != null) s.startSnap()
        else Log.w(AutoSnapService.TAG, "Accessibility service band hai, snap nahi gaya")
    }
}
