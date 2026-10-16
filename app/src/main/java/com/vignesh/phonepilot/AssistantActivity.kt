package com.vignesh.phonepilot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Full-screen assistant:
 *  1. Water ripples spread out from the centre of the screen.
 *  2. The screen clears to a light veil, glowing waves at the bottom move with your voice.
 *  3. Speak — or tap the small bar at the bottom to type.
 */
class AssistantActivity : Activity() {

    private lateinit var waves: WaveView
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private val scope = MainScope()
    private var listenJob: Job? = null
    private var submitted = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this)
        waves = WaveView(this)
        root.addView(waves, FrameLayout.LayoutParams(-1, -1))

        transcript = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 2f, 0x99000000.toInt())
            setPadding(dp(28), 0, dp(28), 0)
            alpha = 0f
        }
        root.addView(transcript, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = dp(80) })

        // Small type bar at the bottom.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xD91C1C22.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            elevation = dp(8).toFloat()
        }
        input = EditText(this).apply {
            hint = "Type a command…"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 1
            setOnEditorActionListener { _, _, _ -> sendTyped(); true }
            setOnFocusChangeListener { _, has -> if (has) stopListening("Typing…") }
        }
        val mic = circleButton("🎙") { input.clearFocus(); hideKeyboard(); startListening() }
        val send = circleButton("➤") { sendTyped() }
        bar.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(mic)
        bar.addView(send)
        val barParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(20))
        }
        root.addView(bar, barParams)

        // Keep the bar above the navigation bar and the keyboard.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
            barParams.bottomMargin = b + dp(16)
            bar.layoutParams = barParams
            insets
        }

        // Tap on empty space = close.
        waves.setOnClickListener { if (!input.hasFocus()) closeAnimated() else { input.clearFocus(); hideKeyboard() } }

        setContentView(root)
        bar.translationY = dp(120).toFloat()
        bar.animate().translationY(0f).setStartDelay(250).setDuration(380).start()
    }

    override fun onResume() {
        super.onResume()
        if (listenJob == null && !submitted) scope.launch { delay(450); startListening() }
    }

    override fun onPause() {
        super.onPause()
        listenJob?.cancel(); listenJob = null
        if (!submitted && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = closeAnimated()

    // ------------------------------------------------------------------ voice

    private fun startListening() {
        val svc = AgentService.instance
        if (svc == null) { showText("Turn on PhonePilot in Accessibility first"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showText("Allow the microphone in the PhonePilot app"); return
        }
        listenJob?.cancel()
        waves.listening = true
        showText("Listening…")
        listenJob = scope.launch {
            val heard = svc.voice.listen(
                onRms = { waves.setLevel(it) },
                onPartial = { showText(it) }
            )
            waves.listening = false
            listenJob = null
            if (!heard.isNullOrBlank()) submit(heard)
            else if (!input.hasFocus()) showText(
                if (svc.voice.lastError in listOf(-1, 12, 13)) "Offline speech not set up — type below"
                else "Didn't catch that. Tap 🎙 or type below"
            )
        }
    }

    private fun stopListening(text: String) {
        listenJob?.cancel(); listenJob = null
        waves.listening = false
        showText(text)
    }

    // ------------------------------------------------------------------ submit / close

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) submit(t)
    }

    private fun submit(text: String) {
        if (submitted) return
        submitted = true
        hideKeyboard()
        showText("“$text”")
        val svc = AgentService.instance
        waves.playOut {
            finish()
            overridePendingTransition(0, android.R.anim.fade_out)
            svc?.scope?.launch { delay(250); svc.runCommand(text) }
        }
    }

    private fun closeAnimated() {
        listenJob?.cancel()
        hideKeyboard()
        waves.playOut { finish(); overridePendingTransition(0, android.R.anim.fade_out) }
    }

    private fun showText(t: String) {
        transcript.text = t
        if (transcript.alpha < 1f) transcript.animate().alpha(1f).setDuration(250).start()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun circleButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x332979FF) }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
        setOnClickListener { onClick() }
    }
}

/**
 * Draws the water-ripple intro, the clearing veil and the voice-reactive glow waves.
 */
class WaveView(ctx: Context) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private var outStart = -1L
    private var onOut: (() -> Unit)? = null
    private var level = 0f
    private var smooth = 0f
    var listening = false

    private val colors = intArrayOf(0xFF00E5FF.toInt(), 0xFF2979FF.toInt(), 0xFFB388FF.toInt(), 0xFF00B0FF.toInt(), 0xFF7C4DFF.toInt())
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    init { isClickable = true }

    /** rmsDb from the speech recognizer, roughly -2 (quiet) to 10 (loud). */
    fun setLevel(rmsDb: Float) { level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f) }

    fun playOut(done: () -> Unit) {
        if (outStart > 0) return
        outStart = SystemClock.uptimeMillis()
        onOut = done
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start).toFloat()
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val maxR = max(w, h) * 0.85f
        val out = if (outStart > 0) ((now - outStart) / 260f).coerceIn(0f, 1f) else 0f
        val fade = 1f - out

        // 1) Veil: darkens fast, then clears to a light tint.
        val veil = when {
            t < 350f -> 0.6f * (t / 350f)
            t < 1150f -> 0.6f - 0.42f * ((t - 350f) / 800f)
            else -> 0.18f
        } * fade
        c.drawColor(Color.argb((veil * 255).toInt(), 4, 8, 22))

        // 2) Water ripples from the centre (first ~1.6 s).
        for (i in 0 until 5) {
            val p = (t - i * 160f) / 1150f
            if (p <= 0f || p >= 1f) continue
            val e = 1f - (1f - p) * (1f - p) * (1f - p) // ease-out
            val r = maxR * e
            val a = ((1f - p) * 0.85f * fade)
            ringPaint.strokeWidth = (22f * (1f - p) + 2f) * density
            ringPaint.color = colors[i % colors.size]
            ringPaint.alpha = (a * 255).toInt()
            // Wobbly edge so it looks like water, not a perfect circle.
            path.reset()
            val steps = 90
            for (k in 0..steps) {
                val ang = (k.toFloat() / steps) * 2f * PI.toFloat()
                val wob = sin(ang * 7f + t * 0.006f + i) * 5f * density * (1f - p)
                val x = cx + (r + wob) * kotlin.math.cos(ang)
                val y = cy + (r + wob) * sin(ang)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, ringPaint)
        }
        // Soft glow at the centre while ripples spread.
        if (t < 1400f) {
            val g = (1f - t / 1400f) * fade
            glowPaint.shader = RadialGradient(cx, cy, maxR * 0.45f,
                intArrayOf(Color.argb((g * 150).toInt(), 0, 229, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, maxR * 0.45f, glowPaint)
        }

        // 3) Voice-reactive glowing waves along the bottom.
        val appear = ((t - 500f) / 600f).coerceIn(0f, 1f) * fade
        if (appear > 0f) {
            val target = if (listening) 0.12f + level * 0.88f else 0.05f
            smooth += (target - smooth) * 0.12f
            val baseY = h * 0.80f
            for (layer in 0 until 3) {
                val amp = (10f + 70f * smooth) * density * (1f - layer * 0.22f)
                val freq = 1.6f + layer * 0.7f
                val speed = 0.0025f + layer * 0.0011f
                path.reset()
                path.moveTo(0f, h)
                var x = 0f
                val step = 6f * density
                while (x <= w + step) {
                    val y = baseY - layer * 18f * density +
                        sin((x / w) * freq * 2f * PI.toFloat() + t * speed + layer * 1.7f) * amp
                    path.lineTo(x, y)
                    x += step
                }
                path.lineTo(w, h)
                path.close()
                val col = colors[layer]
                wavePaint.shader = LinearGradient(0f, baseY - amp - 40f * density, 0f, h,
                    intArrayOf(Color.argb((appear * 170).toInt(), Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP)
                c.drawPath(path, wavePaint)
            }
        }

        if (out >= 1f) { onOut?.invoke(); onOut = null; return }
        postInvalidateOnAnimation()
    }
}
