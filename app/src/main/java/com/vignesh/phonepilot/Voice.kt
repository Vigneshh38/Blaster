package com.vignesh.phonepilot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Ears and mouth.
 *  - listen(): offline speech-to-text using the phone's on-device recognizer.
 *  - speak(): offline text-to-speech.
 */
class Voice(private val ctx: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(ctx, this)
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())

    /** Last listen() error, so callers can tell "no speech" apart from "no recognizer". */
    @Volatile var lastError: Int = 0

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale("en", "IN")
    }

    fun speak(text: String) {
        if (ttsReady) tts.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "phonepilot")
    }

    fun shutdown() {
        tts.stop(); tts.shutdown()
    }

    fun hasRecognizer(): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) || SpeechRecognizer.isRecognitionAvailable(ctx)

    /** Listens once and returns what the user said, or null. Fully offline when on-device recognition exists. */
    suspend fun listen(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            lastError = 0
            val rec = when {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                SpeechRecognizer.isRecognitionAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)
                else -> null
            }
            if (rec == null) {
                lastError = -1
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    rec.destroy()
                    if (cont.isActive) cont.resume(text)
                }
                override fun onError(error: Int) {
                    lastError = error
                    rec.destroy()
                    if (cont.isActive) cont.resume(null)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            rec.startListening(intent)
            cont.invokeOnCancellation { main.post { runCatching { rec.destroy() } } }
        }
    }
}

/**
 * Records your voice as a WAV file so Gemma 4 can listen to it directly (no separate speech-to-text).
 * Stops after ~1.2 s of silence or 10 s total. Use from the app screen (Android only lets
 * apps record the mic while they are open).
 */
@android.annotation.SuppressLint("MissingPermission")
object WavRecorder {
    private const val RATE = 16000

    fun record(ctx: Context, maxMs: Long = 10_000): ByteArray? {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 10) // 100 ms chunks
        val start = System.currentTimeMillis()
        var heardSpeech = false
        var silentMs = 0
        rec.startRecording()
        try {
            while (System.currentTimeMillis() - start < maxMs) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val rms = sqrt(sum / n)
                if (rms > 900) { heardSpeech = true; silentMs = 0 } else silentMs += 100
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(buf[i])
                pcm.write(bb.array())
                if (heardSpeech && silentMs >= 1200) break
            }
        } finally {
            rec.stop(); rec.release()
        }
        if (!heardSpeech) return null
        return wav(pcm.toByteArray())
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}
