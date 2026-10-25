package com.example.autosnap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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

        const val LENS_URL =
            "https://snapchat.com/unlock/?type=SNAPCODE&uuid=d5d091996f1c40faae640f37e39377a3&metadata=01"

        // Lens ke andar gallery kholne ka tap (screen ka hissa 0..1)
        const val LENS_TAP_X = 0.5f
        const val LENS_TAP_Y = 0.5f
        // Gallery me sabse upar wali photo (pehla thumbnail)
        const val THUMB_X = 0.17f
        const val THUMB_Y = 0.30f
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
        ).also { it.acquire(180_000) }

        if (prepareBlackImage() == null) { Log.e(TAG, "black image nahi bani"); finish(); return }

        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(LENS_URL)).apply {
                setPackage(Config.SNAP_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "lens open fail", e)
            finish()
            return
        }
        h.postDelayed({ openGallery() }, 9000)
    }

    private fun openGallery() {
        val n = find("Gallery", false) ?: find("Choose", false) ?: find("Select", false)
        if (n != null) click(n) else tapFrac(LENS_TAP_X, LENS_TAP_Y)
        h.postDelayed({ pickPhoto() }, 3500)
    }

    private fun pickPhoto() {
        tapFrac(THUMB_X, THUMB_Y)
        h.postDelayed({ confirmPhoto() }, 2000)
    }

    private fun confirmPhoto() {
        val n = find("Done", true) ?: find("Add", true) ?: find("Select", true) ?: find("OK", true)
        if (n != null) click(n)
        h.postDelayed({ capture() }, 3500)
    }

    private fun capture() {
        tapFrac(Config.SHUTTER_X, Config.SHUTTER_Y)
        h.postDelayed({ sendTo() }, 3000)
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

    // ---------- Black image ----------

    private fun prepareBlackImage(): Uri? {
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        try {
            sp.getString("black_uri", null)?.let {
                try { contentResolver.delete(Uri.parse(it), null, null) } catch (_: Exception) {}
            }
            val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.BLACK)
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "autosnap_black_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AutoSnap")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sp.edit().putString("black_uri", uri.toString()).apply()
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "black image fail", e)
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
