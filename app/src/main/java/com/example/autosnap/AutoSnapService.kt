package com.example.autosnap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoSnapService : AccessibilityService() {

    companion object {
        const val TAG = "AutoSnap"
        @Volatile var instance: AutoSnapService? = null

        private val EMOJIS = listOf(
            "😀", "😂", "🥰", "😎", "🤩", "😴", "🤔", "🥳", "😇", "🤗",
            "😜", "🙃", "😍", "🤓", "😏", "🥺", "😭", "😡", "🤯", "😱",
            "🤠", "👻", "💀", "👽", "🤖", "🎃", "😺", "🙈", "🐶", "🐱",
            "🐼", "🦁", "🐸", "🐵", "🦄", "🐧", "🐢", "🦋", "🌞", "🌈",
            "🔥", "🌟", "💖", "💯", "🎉", "🍕", "🍔", "🍟", "🍦", "🍩",
            "🍉", "🍓", "⚽", "🏀", "🎮", "🎧", "🚀", "🌸", "🍀"
        )

        private val COLORS = listOf(
            "#FFEB3B", "#4FC3F7", "#F48FB1", "#A5D6A7",
            "#CE93D8", "#FFAB91", "#80CBC4", "#FFF59D"
        )
    }

    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var scrollTries = 0
    private var names: List<String> = emptyList()
    private var nameIdx = 0

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- Flow ----------

    fun startSnap() {
        Log.i(TAG, "startSnap")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "autosnap:wl"
        ).also { it.acquire(150_000) }

        val uri = prepareEmojiImage()
        if (uri == null) { Log.e(TAG, "emoji image nahi bani"); finish(); return }

        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                setPackage(Config.SNAP_PKG)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(share)
        } catch (e: Exception) {
            Log.e(TAG, "share fail", e)
            finish()
            return
        }
        h.postDelayed({ sendTo() }, 7000)
    }

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

    // ---------- Roj ka naya emoji image ----------

    private fun prepareEmojiImage(): Uri? {
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        try {
            // kal wali image hata de
            sp.getString("emoji_uri", null)?.let {
                try { contentResolver.delete(Uri.parse(it), null, null) } catch (_: Exception) {}
            }

            val now = System.currentTimeMillis()
            val day = (now / 86_400_000L).toInt()
            val emoji = EMOJIS[day % EMOJIS.size]
            val bg = Color.parseColor(COLORS[day % COLORS.size])

            val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.drawColor(bg)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 620f
                textAlign = Paint.Align.CENTER
            }
            val fm = paint.fontMetrics
            val y = 960f - (fm.ascent + fm.descent) / 2f
            cv.drawText(emoji, 540f, y, paint)

            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "autosnap_emoji_$day.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.DATE_TAKEN, now)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AutoSnap")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sp.edit().putString("emoji_uri", uri.toString()).apply()
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "emoji image fail", e)
            return null
        }
    }

    // ---------- Helpers ----------

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
