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

        // ---- Tumhare screenshot se nikale hue numbers (screen ka hissa 0..1) ----
        const val PEN_X = 0.917f
        const val PEN_Y = 0.136f
        const val TEXT_X = 0.917f
        const val TEXT_Y = 0.072f
        const val BACK_X = 0.07f
        const val BACK_Y = 0.076f
        const val SLIDER_X = 0.914f
        const val SLIDER_TOP_Y = 0.081f
        const val SLIDER_BOTTOM_Y = 0.248f   // yahan black hai
        const val SNAP_TOP = 0.045f
        const val SNAP_BOTTOM = 0.83f
        const val SENDTO_X = 0.81f
        const val SENDTO_Y = 0.878f

        // Zigzag lines ka gap (pixel me). Khali jagah bache to 9f kar do
        const val FILL_GAP_PX = 12f

        // Emoji bada karne ke liye pinch ka center aur failav
        const val EMOJI_CX = 0.5f
        const val EMOJI_CY = 0.43f
        const val PINCH_END_FRAC = 0.40f

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

    private fun todayEmoji(): String {
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        return EMOJIS[day % EMOJIS.size]
    }

    // ---------- Flow ----------

    fun startSnap() {
        Log.i(TAG, "startSnap")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "autosnap:wl"
        ).also { it.acquire(240_000) }

        val launch = packageManager.getLaunchIntentForPackage(Config.SNAP_PKG)
        if (launch == null) { Log.e(TAG, "Snapchat installed nahi"); finish(); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
        h.postDelayed({ capture() }, 6000)
    }

    private fun capture() {
        tapFrac(Config.SHUTTER_X, Config.SHUTTER_Y)
        h.postDelayed({ openPen() }, 3500)
    }

    // ---------- Poori screen black ----------

    private fun openPen() {
        tapFrac(PEN_X, PEN_Y)
        h.postDelayed({ pickBlack() }, 1500)
    }

    private fun pickBlack() {
        // laal circle ko slider ke neeche tak kheencho = black
        drag(SLIDER_X, SLIDER_TOP_Y, SLIDER_X, SLIDER_BOTTOM_Y, 900)
        h.postDelayed({ fillScreen() }, 1800)
    }

    private fun fillScreen() {
        val d = resources.displayMetrics
        val w = d.widthPixels.toFloat()
        val ht = d.heightPixels.toFloat()
        val left = w * 0.02f
        val right = w * 0.985f
        val top = ht * SNAP_TOP
        var y = ht * SNAP_BOTTOM
        var toRight = true
        val path = Path()
        path.moveTo(left, y)
        while (y >= top) {
            path.lineTo(if (toRight) right else left, y)
            y -= FILL_GAP_PX
            path.lineTo(if (toRight) right else left, y)
            toRight = !toRight
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 30000))
            .build()
        dispatchGesture(g, null, null)
        h.postDelayed({ closePen() }, 31500)
    }

    private fun closePen() {
        tapFrac(BACK_X, BACK_Y)
        h.postDelayed({ openCaption() }, 1800)
    }

    // ---------- Emoji ----------

    private fun openCaption() {
        tapFrac(TEXT_X, TEXT_Y)
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
        h.postDelayed({ pinchEmoji() }, 1800)
    }

    private fun pinchEmoji() {
        val d = resources.displayMetrics
        val cx = d.widthPixels * EMOJI_CX
        val cy = d.heightPixels * EMOJI_CY
        val startGap = d.widthPixels * 0.04f
        val endGap = d.widthPixels * PINCH_END_FRAC
        val p1 = Path().apply { moveTo(cx - startGap, cy); lineTo(cx - endGap, cy) }
        val p2 = Path().apply { moveTo(cx + startGap, cy); lineTo(cx + endGap, cy) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 900))
            .addStroke(GestureDescription.StrokeDescription(p2, 0, 900))
            .build()
        dispatchGesture(g, null, null)
        h.postDelayed({ sendTo() }, 2000)
    }

    // ---------- Send ----------

    private fun sendTo() {
        val n = find("Send To", false)
        if (n != null) click(n) else tapFrac(SENDTO_X, SENDTO_Y)
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

    private fun drag(fx1: Float, fy1: Float, fx2: Float, fy2: Float, dur: Long) {
        val d = resources.displayMetrics
        val p = Path().apply {
            moveTo(d.widthPixels * fx1, d.heightPixels * fy1)
            lineTo(d.widthPixels * fx2, d.heightPixels * fy2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            .build()
        dispatchGesture(g, null, null)
    }
}
