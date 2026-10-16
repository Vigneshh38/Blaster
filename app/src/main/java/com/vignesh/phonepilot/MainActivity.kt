package com.vignesh.phonepilot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Setup screen: download the model, give permissions, turn on the service, test commands. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var historyText: TextView
    private lateinit var lastLogText: TextView
    private lateinit var commandBox: EditText
    private val pickModel = 42

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(40))
            setBackgroundColor(0xFF101010.toInt())
        }
        fun title(t: String, size: Float = 15f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(14), 0, dp(6))
            if (size > 16) typeface = Typeface.DEFAULT_BOLD
        }
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextColor(Color.WHITE); gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF263238.toInt()) }
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

        root.addView(title("PhonePilot", 26f))
        root.addView(TextView(this).apply {
            text = "Offline AI that controls your phone. Brain: Gemma 4 E4B (runs on this phone's GPU)."
            setTextColor(0xFFB0BEC5.toInt())
        })

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF1B2631.toInt()) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        }
        root.addView(statusText)

        root.addView(title("Setup (do these once, in order)", 17f))
        root.addView(btn("1. Download the brain (3.7 GB, use Wi-Fi)") {
            ModelManager.startDownload(this); toast("Download started. Check the notification.")
        })
        root.addView(btn("   …or pick a model file I already downloaded") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), pickModel)
        })
        root.addView(btn("2. Give permissions (mic, calls, SMS, contacts)") { requestPermissions(permissions, 1) })
        root.addView(btn("3. Allow restricted settings (needed once on Android 13+)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            toast("Tap ⋮ (top right) → Allow restricted settings")
        })
        root.addView(btn("4. Turn on PhonePilot in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Installed apps → PhonePilot → turn ON")
        })

        root.addView(Switch(this).apply {
            text = "Ask me before sending, paying, deleting or calling"
            setTextColor(Color.WHITE)
            isChecked = ModelManager.askBeforeSensitive(this@MainActivity)
            setOnCheckedChangeListener { _, v -> ModelManager.setAskBeforeSensitive(this@MainActivity, v) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        })

        root.addView(title("Try a command", 17f))
        commandBox = EditText(this).apply {
            hint = "e.g. set an alarm for 6:30 am"
            setTextColor(Color.WHITE); setHintTextColor(0xFF78909C.toInt())
        }
        root.addView(commandBox)
        root.addView(btn("▶ Run typed command (goes to home screen first)") {
            val svc = AgentService.instance ?: return@btn toast("Turn on PhonePilot in Accessibility first.")
            val cmd = commandBox.text.toString().trim()
            if (cmd.isEmpty()) return@btn
            moveTaskToBack(true)
            handler.postDelayed({ svc.runCommand(cmd) }, 700)
        })
        root.addView(btn("🎙 Speak to Gemma directly (it listens to your voice itself)") { speakToGemma() })

        root.addView(TextView(this).apply {
            text = "Anywhere on the phone: long-press the blue 🎙 bubble and speak, or tap it to type. " +
                "While it works the bubble turns red ■ — tap it to stop."
            setTextColor(0xFFB0BEC5.toInt()); setPadding(0, dp(14), 0, 0)
        })

        root.addView(title("Recent commands", 17f))
        historyText = TextView(this).apply { setTextColor(0xFFCFD8DC.toInt()) }
        root.addView(historyText)
        root.addView(title("Last run details (step timings)", 17f))
        lastLogText = TextView(this).apply { setTextColor(0xFF90A4AE.toInt()); textSize = 12f; setTextIsSelectable(true) }
        root.addView(lastLogText)

        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF101010.toInt()); addView(root) })
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun refresh() {
        val model = ModelManager.copyProgress
            ?: ModelManager.downloadState(this)
            ?: if (ModelManager.isReady(this)) "✅ Ready" else "❌ Not downloaded"
        val service = if (AgentService.instance != null) "✅ On" else "❌ Off"
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val perms = if (missing.isEmpty()) "✅ All given" else "❌ Missing ${missing.size}"
        val brain = when {
            Brain.engine != null -> "✅ Loaded on ${Brain.backendName}"
            Brain.lastError != null -> "❌ ${Brain.lastError}"
            else -> "⏳ Loads when service starts"
        }
        val speech = if (AgentService.instance?.voice?.hasRecognizer() == true) "✅ Available" else "–"
        statusText.text = "Model file: $model\nAccessibility: $service\nPermissions: $perms\nBrain: $brain\nOffline speech: $speech"

        lastLogText.text = ModelManager.prefs(this).getString("last_log", "No runs yet.")
        val arr = runCatching { JSONArray(ModelManager.prefs(this).getString("history", "[]")) }.getOrElse { JSONArray() }
        historyText.text = if (arr.length() == 0) "None yet." else buildString {
            for (i in arr.length() - 1 downTo maxOf(0, arr.length() - 8)) {
                val o = arr.getJSONObject(i)
                append("• ").append(o.optString("c")).append("\n   → ").append(o.optString("r")).append("\n")
            }
        }
    }

    private fun speakToGemma() {
        val svc = AgentService.instance ?: return toast("Turn on PhonePilot in Accessibility first.")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        toast("Listening… speak now")
        AppScope.launch {
            val wav = WavRecorder.record(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (wav == null) { toast("I didn't hear anything."); return@withContext }
                moveTaskToBack(true)
                handler.postDelayed({ svc.runCommand(null, wav) }, 700)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == pickModel && resultCode == RESULT_OK) {
            toast("Copying model… keep the app open")
            AppScope.launch {
                val ok = runCatching { ModelManager.copyFrom(applicationContext, uri) }.isSuccess
                withContext(Dispatchers.Main) { toast(if (ok) "Model copied ✅" else "Copy failed") }
            }
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
