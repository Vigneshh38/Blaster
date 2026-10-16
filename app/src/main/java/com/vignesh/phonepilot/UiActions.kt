package com.vignesh.phonepilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** The "hands": tap, type, scroll, swipe, keys and screenshots. */
object UiActions {

    suspend fun tap(svc: AgentService, el: UiElement): Boolean {
        // Walk up to the nearest clickable parent (text inside a button is often not clickable itself).
        var n: AccessibilityNodeInfo? = el.node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapAt(svc, el.bounds.exactCenterX(), el.bounds.exactCenterY(), 60)
    }

    suspend fun longPress(svc: AgentService, el: UiElement): Boolean {
        var n: AccessibilityNodeInfo? = el.node
        while (n != null && !n.isLongClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        return tapAt(svc, el.bounds.exactCenterX(), el.bounds.exactCenterY(), 800)
    }

    suspend fun tapPercent(svc: AgentService, xPct: Double, yPct: Double): Boolean {
        val (w, h) = screenSize(svc)
        return tapAt(svc, (w * xPct / 100).toFloat(), (h * yPct / 100).toFloat(), 60)
    }

    suspend fun typeText(svc: AgentService, el: UiElement?, text: String, pressEnter: Boolean): String {
        var target: AccessibilityNodeInfo? = el?.node
        if (target != null && !target.isEditable) {
            tap(svc, el!!)
            delay(250)
            target = null
        }
        if (target == null) target = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) return "No text box is focused. Tap a text box first."
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return "Could not type into that element."
        if (pressEnter) {
            delay(150)
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return "Typed \"$text\"" + if (pressEnter) " and pressed Enter." else "."
    }

    /** direction = where you want to move the content: "down" shows what is below. */
    suspend fun scroll(svc: AgentService, direction: String, el: UiElement?): Boolean {
        if (el != null) {
            var n: AccessibilityNodeInfo? = el.node
            while (n != null && !n.isScrollable) n = n.parent
            if (n != null) {
                val action = if (direction == "down" || direction == "right")
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (n.performAction(action)) return true
            }
        }
        return when (direction) {
            "down" -> swipePercent(svc, 50.0, 72.0, 50.0, 28.0, 350)
            "up" -> swipePercent(svc, 50.0, 28.0, 50.0, 72.0, 350)
            "right" -> swipePercent(svc, 80.0, 50.0, 20.0, 50.0, 300)
            "left" -> swipePercent(svc, 20.0, 50.0, 80.0, 50.0, 300)
            else -> false
        }
    }

    suspend fun swipePercent(svc: AgentService, x1: Double, y1: Double, x2: Double, y2: Double, ms: Long = 350): Boolean {
        val (w, h) = screenSize(svc)
        val p = Path().apply {
            moveTo((w * x1 / 100).toFloat(), (h * y1 / 100).toFloat())
            lineTo((w * x2 / 100).toFloat(), (h * y2 / 100).toFloat())
        }
        return gesture(svc, p, ms)
    }

    fun pressKey(svc: AgentService, key: String): Boolean {
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return svc.performGlobalAction(action)
    }

    /** Waits until the screen stops changing (max [maxMs]). */
    suspend fun waitForIdle(svc: AgentService, maxMs: Long = 1500) {
        delay(120)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - svc.lastEventAt > 280) return
            delay(100)
        }
    }

    /** Screenshot as a small JPEG so the model can "see" the screen (for games, maps, images). */
    suspend fun screenshotJpeg(svc: AgentService): ByteArray? = suspendCancellableCoroutine { cont ->
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bytes = try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            soft?.let {
                                val scale = 768f / it.width
                                val small = Bitmap.createScaledBitmap(it, 768, (it.height * scale).toInt(), true)
                                ByteArrayOutputStream().use { out ->
                                    small.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                    out.toByteArray()
                                }
                            }
                        } catch (_: Exception) { null }
                        if (cont.isActive) cont.resume(bytes)
                    }
                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    // ---- helpers ----

    private suspend fun tapAt(svc: AgentService, x: Float, y: Float, ms: Long): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(svc, p, ms)
    }

    private suspend fun gesture(svc: AgentService, path: Path, ms: Long): Boolean {
        svc.overlay.setPassThrough(true) // so our floating button never blocks the gesture
        try {
            return suspendCancellableCoroutine { cont ->
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                    .build()
                val started = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!started && cont.isActive) cont.resume(false)
            }
        } finally {
            svc.overlay.setPassThrough(false)
        }
    }

    /** Finds the first visible node on screen that matches [pred]. */
    fun findNode(svc: AgentService, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { svc.windows.forEach { w -> w.root?.let { roots.add(it) } } }
        svc.rootInActiveWindow?.let { roots.add(it) }
        for (r in roots) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            while (q.isNotEmpty()) {
                val n = q.removeFirst()
                if (n.isVisibleToUser && pred(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
        }
        return null
    }

    /** Waits up to [timeoutMs] for a matching node to appear. */
    suspend fun waitForNode(svc: AgentService, timeoutMs: Long, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            findNode(svc, pred)?.let { return it }
            delay(200)
        }
        return null
    }

    suspend fun tapNode(svc: AgentService, node: AccessibilityNodeInfo): Boolean {
        val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return tap(svc, UiElement(node, "", b))
    }

    fun screenSize(ctx: android.content.Context): Pair<Int, Int> = try {
        val b = ctx.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        b.width() to b.height()
    } catch (_: Exception) {
        val m = ctx.resources.displayMetrics
        m.widthPixels to m.heightPixels
    }
}
