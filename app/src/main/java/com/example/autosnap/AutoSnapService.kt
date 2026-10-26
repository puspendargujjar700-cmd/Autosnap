package com.example.autosnap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoSnapService : AccessibilityService() {

    companion object {
        const val TAG = "AutoSnap"
        @Volatile var instance: AutoSnapService? = null

        // ---- Tune karne wale numbers (screen ka hissa 0..1) ----
        // Pen (draw) icon ki jagah, agar naam se na mile
        const val PEN_X = 0.93f
        const val PEN_Y = 0.20f
        // Rang wali patti (slider) ki x jagah
        const val COLOR_X = 0.93f
        // Zigzag lines ke beech ka gap (dp me). Chhota = zyada pakka fill, par der lagti hai
        const val FILL_GAP_DP = 10f
        // Caption kholne ke liye snap par tap
        const val CAPTION_TAP_X = 0.5f
        const val CAPTION_TAP_Y = 0.45f

        // Roj alag rang: slider ki y jagah
        private val COLOR_Y = listOf(0.30f, 0.38f, 0.46f, 0.54f, 0.62f, 0.70f, 0.78f)

        private val EMOJIS = listOf(
            "😀", "😂", "🥰", "😎", "🤩", "😴", "🤔", "🥳", "😇", "🤗",
            "😜", "🙃", "😍", "🤓", "😏", "🥺", "😭", "😡", "🤯", "😱",
            "🤠", "👻", "💀", "👽", "🤖", "🎃", "😺", "🙈", "🐶", "🐱",
            "🐼", "🦁", "🐸", "🐵", "🦄", "🐧", "🐢", "🦋", "🌞", "🌈",
            "🔥", "🌟", "💖", "💯", "🎉", "🍕", "🍔", "🍟", "🍦", "🍩",
            "🍉", "🍓", "⚽", "🏀", "🎮", "🎧", "🚀", "🌸", "🍀"
        )
    }

    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var scrollTries = 0
    private var names: List<String> = emptyList()
    private var nameIdx = 0
    private var typed = false

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun dayIndex(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()
    private fun todayEmoji(): String = EMOJIS[dayIndex() % EMOJIS.size]

    // ---------- Flow ----------

    fun startSnap() {
        Log.i(TAG, "startSnap")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "autosnap:wl"
        ).also { it.acquire(200_000) }

        val launch = packageManager.getLaunchIntentForPackage(Config.SNAP_PKG)
        if (launch == null) { Log.e(TAG, "Snapchat installed nahi"); finish(); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
        h.postDelayed({ capture() }, 6000)
    }

    private fun capture() {
        tapFrac(Config.SHUTTER_X, Config.SHUTTER_Y)
        h.postDelayed({ openPen() }, 3000)
    }

    // ---------- Background ek rang ka ----------

    private fun openPen() {
        val pen = find("Draw", false) ?: find("Pen", false) ?: find("Pencil", false)
        if (pen != null) click(pen) else tapFrac(PEN_X, PEN_Y)
        h.postDelayed({ pickColor() }, 1200)
    }

    private fun pickColor() {
        val y = COLOR_Y[dayIndex() % COLOR_Y.size]
        tapFrac(COLOR_X, y)
        h.postDelayed({ fillScreen() }, 800)
    }

    private fun fillScreen() {
        val d = resources.displayMetrics
        val w = d.widthPixels.toFloat()
        val ht = d.heightPixels.toFloat()
        val gap = FILL_GAP_DP * d.density
        val path = Path()
        var y = ht * 0.06f
        var leftToRight = true
        path.moveTo(w * 0.04f, y)
        while (y < ht * 0.94f) {
            path.lineTo(if (leftToRight) w * 0.88f else w * 0.04f, y)
            y += gap
            path.lineTo(if (leftToRight) w * 0.88f else w * 0.04f, y)
            leftToRight = !leftToRight
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 9000))
            .build()
        dispatchGesture(g, null, null)
        h.postDelayed({ closePen() }, 10000)
    }

    private fun closePen() {
        val done = find("Done", true)
        if (done != null) click(done) else tapFrac(PEN_X, PEN_Y)
        h.postDelayed({ openCaption() }, 1500)
    }

    // ---------- Emoji caption ----------

    private fun openCaption() {
        tapFrac(CAPTION_TAP_X, CAPTION_TAP_Y)
        h.postDelayed({ typeEmoji() }, 1500)
    }

    private fun typeEmoji() {
        val emoji = todayEmoji()
        typed = false
        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root?.let { findEditable(it) }
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, emoji)
            }
            var ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!ok) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("emoji", emoji))
                ok = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            typed = ok
        } else {
            Log.e(TAG, "caption box nahi mila")
        }
        h.postDelayed({ closeCaption() }, 1200)
    }

    private fun closeCaption() {
        val done = find("Done", true)
        if (done != null) {
            click(done)
        } else if (typed) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        h.postDelayed({ sendTo() }, 1500)
    }

    // ---------- Send ----------

    private fun sendTo() {
        val n = find("Send To", false)
        if (n != null) click(n) else tapFrac(Config.SEND_TO_X, Config.SEND_TO_Y)
        names = Config.friend(this).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        nameIdx = 0
        scrollTries = 0
        h.postDelayed({ pickFriend() }, 2500)
    }

    private fun pickFriend() {
        if (nameIdx >= names.size) {
            h.postDelayed({ send() }, 800)
            return
        }
        val n = find(names[nameIdx], false)
        if (n != null) {
            click(n)
            nameIdx++
            scrollTries = 0
            h.postDelayed({ pickFriend() }, 900)
        } else if (scrollTries < 6) {
            scrollTries++
            rootInActiveWindow?.let { scrollDown(it) }
            h.postDelayed({ pickFriend() }, 1200)
        } else {
            Log.e(TAG, "Friend '${names[nameIdx]}' nahi mila, skip")
            nameIdx++
            scrollTries = 0
            h.postDelayed({ pickFriend() }, 300)
        }
    }

    private fun send() {
        val n = find("Send", true)
        if (n != null) click(n) else tapFrac(Config.SEND_X, Config.SEND_Y)
        h.postDelayed({ finish() }, 4000)
    }

    private fun finish() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (wl?.isHeld == true) wl?.release()
    }

    // ---------- Helpers ----------

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }

    private fun find(text: String, exact: Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return search(root, text, exact)
    }

    private fun search(n: AccessibilityNodeInfo, t: String, exact: Boolean): AccessibilityNodeInfo? {
        val s = n.text?.toString() ?: ""
        val d = n.contentDescription?.toString() ?: ""
        val hit = if (exact) s.equals(t, true) || d.equals(t, true)
        else s.contains(t, true) || d.contains(t, true)
        if (hit) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            search(c, t, exact)?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect()
        node.getBoundsInScreen(r)
        tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun scrollDown(n: AccessibilityNodeInfo): Boolean {
        if (n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (scrollDown(c)) return true
        }
        return false
    }

    private fun tapFrac(fx: Float, fy: Float) {
        val d = resources.displayMetrics
        tap(d.widthPixels * fx, d.heightPixels * fy)
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 80))
            .build()
        dispatchGesture(g, null, null)
    }
}
