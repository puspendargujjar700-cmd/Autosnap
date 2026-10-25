package com.example.autosnap

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { f() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply { text = "AutoSnap v2"; textSize = 24f })

        val tp = TimePicker(this).apply {
            setIs24HourView(true)
            hour = Config.hour(this@MainActivity)
            minute = Config.minute(this@MainActivity)
        }
        col.addView(tp)

        val friend = EditText(this).apply {
            hint = "Friends ke naam, comma se alag (karan, rahul)"
            setText(Config.friend(this@MainActivity))
        }
        col.addView(friend)

        val sw = Switch(this).apply {
            text = "Roj auto snap ON"
            isChecked = Config.enabled(this@MainActivity)
        }
        col.addView(sw)

        col.addView(btn("Save") {
            Config.save(this, tp.hour, tp.minute, sw.isChecked, friend.text.toString())
            Scheduler.schedule(this)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })
        col.addView(btn("1. Accessibility ON karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("2. Battery optimization hatao") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        col.addView(btn("3. Abhi test karo") {
            Config.save(this, tp.hour, tp.minute, sw.isChecked, friend.text.toString())
            val s = AutoSnapService.instance
            if (s == null) Toast.makeText(this, "Pehle Accessibility service ON karo", Toast.LENGTH_LONG).show()
            else s.startSnap()
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
