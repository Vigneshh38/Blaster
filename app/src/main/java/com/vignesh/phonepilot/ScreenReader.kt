package com.vignesh.phonepilot

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** One readable/tappable thing on the screen. */
class UiElement(val node: AccessibilityNodeInfo, val label: String, val bounds: Rect)

/** What is on the screen right now, as text the model can read. */
class ScreenSnapshot(val text: String, val elements: List<UiElement>, val packageName: String)

/**
 * Turns the screen into a numbered list like:
 *   [3] Button "Send" (tap) @(92%,88%)
 * The model answers with tap(element_id=3), and we tap that exact element.
 */
object ScreenReader {
    private const val MAX_ELEMENTS = 45
    private const val MAX_TEXT = 45

    fun capture(svc: AccessibilityService): ScreenSnapshot {
        val (w, h) = UiActions.screenSize(svc)
        val screenW = w.coerceAtLeast(1)
        val screenH = h.coerceAtLeast(1)

        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in svc.windows) {
                val keep = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> true
                    AccessibilityWindowInfo.TYPE_SYSTEM -> w.isActive || w.isFocused // notification shade, dialogs
                    else -> false // skip our own overlay, keyboard, etc.
                }
                if (keep) w.root?.let { roots.add(it) }
            }
        } catch (_: Exception) { }
        if (roots.isEmpty()) svc.rootInActiveWindow?.let { roots.add(it) }

        val pkg = svc.rootInActiveWindow?.packageName?.toString() ?: roots.firstOrNull()?.packageName?.toString() ?: "unknown"
        val elements = mutableListOf<UiElement>()
        val lines = StringBuilder()
        var truncated = false

        fun walk(n: AccessibilityNodeInfo) {
            if (elements.size >= MAX_ELEMENTS) { truncated = true; return }
            if (!n.isVisibleToUser) return
            val text = clean(n.text)
            val desc = clean(n.contentDescription)
            val hint = clean(n.hintText)
            val interactive = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (interactive || text.isNotEmpty() || desc.isNotEmpty()) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                if (b.width() > 0 && b.height() > 0) {
                    val label = listOf(text, desc, hint).filter { it.isNotEmpty() }.distinct().joinToString(" | ")
                    val id = elements.size
                    elements += UiElement(n, label, b)
                    lines.append('[').append(id).append("] ").append(role(n))
                    if (label.isNotEmpty()) lines.append(" \"").append(label).append('"')
                    val flags = mutableListOf<String>()
                    if (n.isClickable) flags += "tap"
                    if (n.isEditable) flags += "input"
                    if (n.isScrollable) flags += "scroll"
                    if (n.isCheckable) flags += if (n.isChecked) "ON" else "OFF"
                    if (n.isSelected) flags += "selected"
                    if (n.isFocused) flags += "focused"
                    if (!n.isEnabled) flags += "disabled"
                    if (flags.isNotEmpty()) lines.append(" (").append(flags.joinToString(",")).append(')')
                    val cx = (b.centerX() * 100 / screenW).coerceIn(0, 100)
                    val cy = (b.centerY() * 100 / screenH).coerceIn(0, 100)
                    lines.append(" @(").append(cx).append("%,").append(cy).append("%)\n")
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
            }
        }
        roots.forEach { walk(it) }

        val header = "App on screen: ${appLabel(svc, pkg)} ($pkg)\n"
        val body = when {
            elements.isEmpty() -> "(No readable elements. Call look_at_screen to see an image of the screen.)\n"
            else -> "Screen elements ([id] type \"text\" (state) @(x%,y%)):\n$lines" +
                if (truncated) "(more elements exist: scroll to see them)\n" else ""
        }
        return ScreenSnapshot(header + body, elements, pkg)
    }

    private fun clean(cs: CharSequence?): String {
        val s = cs?.toString()?.replace('\n', ' ')?.replace('"', '\'')?.trim().orEmpty()
        return if (s.length > MAX_TEXT) s.take(MAX_TEXT) + "…" else s
    }

    private fun role(n: AccessibilityNodeInfo): String {
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
        return when {
            n.isEditable || cls.contains("EditText") -> "Input"
            cls.contains("Switch") -> "Switch"
            cls.contains("CheckBox") -> "Checkbox"
            cls.contains("RadioButton") -> "Radio"
            cls.contains("Button") -> "Button"
            cls.contains("Image") -> "Image"
            cls.contains("Text") -> "Text"
            n.isScrollable -> "List"
            n.isClickable -> "Item"
            else -> cls
        }
    }

    fun appLabel(svc: AccessibilityService, pkg: String): String = try {
        val pm = svc.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
