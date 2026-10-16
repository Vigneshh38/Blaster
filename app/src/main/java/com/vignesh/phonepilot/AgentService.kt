package com.vignesh.phonepilot

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The always-running part of PhonePilot.
 * Android's Accessibility Service lets it read any screen and tap/type/scroll for you.
 */
class AgentService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentService? = null
            private set
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var overlay: Overlay
        private set
    lateinit var voice: Voice
        private set
    @Volatile var lastEventAt = 0L
        private set
    private var job: Job? = null

    val isRunning get() = job?.isActive == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = Overlay(this)
        voice = Voice(this)
        overlay.show()
        // Load the model in the background so the first command is fast.
        if (ModelManager.isReady(this)) {
            scope.launch {
                overlay.status("Loading Gemma 4 brain…")
                runCatching { Brain.ensureLoaded(this@AgentService) }
                    .onSuccess { overlay.status("Ready (${Brain.backendName}). Long-press 🎙 to speak.") }
                    .onFailure { overlay.status("Model failed to load: ${it.message}") }
            }
        } else {
            overlay.status("PhonePilot: open the app and download the model first.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        job?.cancel()
        runCatching { overlay.hide() }
        runCatching { voice.shutdown() }
        scope.cancel()
        super.onDestroy()
    }

    fun runCommand(text: String?, audio: ByteArray? = null) {
        if (isRunning) { overlay.status("Busy. Tap ■ to stop the current task."); return }
        if (!ModelManager.isReady(this)) { overlay.status("Model not downloaded. Open PhonePilot app."); return }
        job = scope.launch { Agent(this@AgentService).run(text, audio) }
    }

    fun stopCommand() {
        job?.cancel()
        overlay.status("Stopped.")
        overlay.setRunning(false)
    }

    fun listenAndRun() {
        scope.launch {
            overlay.status("Listening…")
            val heard = voice.listen()
            if (heard.isNullOrBlank()) overlay.speechProblem()
            else { overlay.status("You said: \"$heard\""); runCommand(heard) }
        }
    }

    /** Keeps the last 30 commands so the app screen can show them. */
    fun addHistory(command: String, result: String) {
        val p = ModelManager.prefs(this)
        val arr = runCatching { JSONArray(p.getString("history", "[]")) }.getOrElse { JSONArray() }
        arr.put(JSONObject().put("c", command).put("r", result).put("t", System.currentTimeMillis()))
        while (arr.length() > 30) arr.remove(0)
        p.edit().putString("history", arr.toString()).apply()
    }
}
