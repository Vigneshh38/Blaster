package com.vignesh.phonepilot

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The floating UI shown over every app:
 *  - a round mic bubble (tap = open command box, long-press = speak, tap while working = STOP)
 *  - a small status line at the top (what the agent is doing)
 *  - a panel for typing commands, Yes/No confirmations and questions
 * Uses TYPE_ACCESSIBILITY_OVERLAY, so no extra "draw over apps" permission is needed.
 */
class Overlay(private val svc: AgentService) {

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()

    private lateinit var bubble: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var statusView: TextView
    private lateinit var statusParams: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var panelTitle: TextView
    private lateinit var input: EditText
    private lateinit var rowCommand: LinearLayout
    private lateinit var rowConfirm: LinearLayout

    private var shown = false
    private var panelShown = false
    private var running = false
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    private var pendingAnswer: CompletableDeferred<String?>? = null
    private val hideStatus = Runnable { if (!running) statusView.visibility = View.GONE }

    // ------------------------------------------------------------------ build

    fun show() {
        if (shown) return
        shown = true
        buildBubble(); buildStatus(); buildPanel()
        wm.addView(statusView, statusParams)
        wm.addView(bubble, bubbleParams)
    }

    fun hide() {
        if (!shown) return
        shown = false
        runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(statusView) }
        if (panelShown) runCatching { wm.removeView(panel) }
        panelShown = false
    }

    private fun baseParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        bubble = TextView(svc).apply {
            text = "🎙"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xEE1E88E5.toInt()) }
            elevation = dp(6).toFloat()
        }
        bubbleParams = baseParams(dp(56), dp(56),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            gravity = Gravity.TOP or Gravity.START
            x = svc.resources.displayMetrics.widthPixels - dp(64)
            y = svc.resources.displayMetrics.heightPixels / 2
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val longPress = Runnable { if (!moved) { moved = true; onBubbleLongPress() } }
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; moved = false
                    main.postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        if (!moved) main.removeCallbacks(longPress)
                        moved = true
                        bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!moved) onBubbleTap()
                }
            }
            true
        }
    }

    private fun buildStatus() {
        statusView = TextView(svc).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xDD222222.toInt()) }
            maxLines = 3
            visibility = View.GONE
        }
        statusParams = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(svc).apply {
        text = label
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(color) }
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        setOnClickListener { onClick() }
    }

    private fun buildPanel() {
        panelTitle = TextView(svc).apply {
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        input = EditText(svc).apply {
            hint = "Type a command…"
            setHintTextColor(0xFF999999.toInt()); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF333333.toInt()) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnEditorActionListener { _, _, _ -> submitText(); true }
        }
        rowCommand = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("🎙 Speak", 0xFF1E88E5.toInt()) { onMicInPanel() })
            addView(button("Go ▶", 0xFF43A047.toInt()) { submitText() })
            addView(button("Close", 0xFF555555.toInt()) { closePanel(); pendingAnswer?.complete(null) })
        }
        rowConfirm = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Yes, do it", 0xFF43A047.toInt()) { pendingConfirm?.complete(true) })
            addView(button("No", 0xFFE53935.toInt()) { pendingConfirm?.complete(false) })
        }
        panel = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xF2181818.toInt()) }
            elevation = dp(10).toFloat()
            addView(panelTitle)
            addView(input)
            addView(rowCommand)
            addView(rowConfirm)
        }
        panelParams = baseParams((svc.resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(90)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
    }

    // ------------------------------------------------------------------ panel modes

    private fun openPanel(title: String, withInput: Boolean, confirmMode: Boolean) = onMain {
        panelTitle.text = title
        input.visibility = if (withInput) View.VISIBLE else View.GONE
        rowCommand.visibility = if (confirmMode) View.GONE else View.VISIBLE
        rowConfirm.visibility = if (confirmMode) View.VISIBLE else View.GONE
        input.setText("")
        if (!panelShown) { wm.addView(panel, panelParams); panelShown = true }
        if (withInput) input.requestFocus()
    }

    private fun closePanel() = onMain {
        if (panelShown) { runCatching { wm.removeView(panel) }; panelShown = false }
    }

    private fun submitText() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val answer = pendingAnswer
        closePanel()
        if (answer != null) answer.complete(t) else svc.runCommand(t)
    }

    private fun onMicInPanel() {
        val answer = pendingAnswer
        closePanel()
        svc.scope.launch {
            status("Listening…")
            val heard = svc.voice.listen()
            if (answer != null) { answer.complete(heard); return@launch }
            if (heard.isNullOrBlank()) speechProblem() else svc.runCommand(heard)
        }
    }

    private fun onBubbleTap() {
        if (running) { svc.stopCommand(); return }
        if (panelShown) closePanel() else openPanel("What should I do?", withInput = true, confirmMode = false)
    }

    private fun onBubbleLongPress() {
        if (running) return
        svc.listenAndRun()
    }

    fun speechProblem() {
        val err = svc.voice.lastError
        if (err == -1 || err == 12 || err == 13) { // no recognizer / language not available offline
            status("Offline speech not set up. Type your command, or see the app for the fix.")
            openPanel("Type a command:", withInput = true, confirmMode = false)
        } else status("Didn't catch that. Long-press the mic and try again.")
    }

    // ------------------------------------------------------------------ public API used by the agent

    fun status(text: String) = onMain {
        statusView.text = text
        statusView.visibility = View.VISIBLE
        main.removeCallbacks(hideStatus)
        if (!running) main.postDelayed(hideStatus, 6000)
    }

    fun setRunning(r: Boolean) = onMain {
        running = r
        bubble.text = if (r) "■" else "🎙"
        (bubble.background as GradientDrawable).setColor(if (r) 0xEEE53935.toInt() else 0xEE1E88E5.toInt())
        if (!r) main.postDelayed(hideStatus, 6000)
    }

    /** While a gesture is running, let touches pass through the bubble. */
    fun setPassThrough(p: Boolean) = onMain {
        if (!shown) return@onMain
        bubbleParams.flags = if (p)
            bubbleParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
    }

    /** Shows Yes/No and waits up to 45 s. No answer = No. */
    suspend fun confirm(question: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingConfirm = d
        openPanel("Allow this?\n$question", withInput = false, confirmMode = true)
        svc.voice.speak("Should I ${question.lowercase()}?")
        val r = withTimeoutOrNull(45_000) { d.await() } ?: false
        pendingConfirm = null
        closePanel()
        return r
    }

    /** Asks the user a question; they can type or tap Speak. */
    suspend fun ask(question: String): String? {
        val d = CompletableDeferred<String?>()
        pendingAnswer = d
        openPanel(question, withInput = true, confirmMode = false)
        svc.voice.speak(question)
        val r = withTimeoutOrNull(90_000) { d.await() }
        pendingAnswer = null
        closePanel()
        return r
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
