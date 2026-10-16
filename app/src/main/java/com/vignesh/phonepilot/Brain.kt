package com.vignesh.phonepilot

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Holds the Gemma 4 E4B model in memory (the "brain").
 * Loading takes ~5–10 s, so it is loaded once and kept.
 * All model calls run on one background thread, one at a time.
 */
object Brain {
    val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phonepilot-llm").apply { priority = Thread.MAX_PRIORITY }
    }.asCoroutineDispatcher()

    @Volatile var engine: Engine? = null
        private set
    @Volatile var backendName: String = ""
        private set
    @Volatile var lastError: String? = null
        private set

    private val lock = Mutex()

    @Volatile private var loadedPath: String? = null

    suspend fun ensureLoaded(ctx: Context): Engine = lock.withLock {
        val wanted = ModelManager.modelFile(ctx).absolutePath
        val current = engine
        if (current != null && loadedPath == wanted) return@withLock current
        withContext(dispatcher) {
            runCatching { current?.close() }
            engine = null
            load(ctx.applicationContext).also { loadedPath = wanted }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun load(ctx: Context): Engine {
        val file = ModelManager.modelFile(ctx)
        check(ModelManager.isReady(ctx)) { "Model not downloaded yet. Open PhonePilot and download it." }

        // Multi-Token Prediction: makes Gemma 4 answer ~2x faster on the GPU.
        runCatching { ExperimentalFlags.enableSpeculativeDecoding = true }

        // Try the GPU first (fast), then fall back to the CPU (slower but always works).
        val attempts: List<Pair<String, () -> Backend>> = listOf(
            "GPU" to { Backend.GPU() },
            "CPU" to { Backend.CPU() },
        )
        var last: Throwable? = null
        for ((name, makeBackend) in attempts) {
            try {
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = makeBackend(),
                    visionBackend = makeBackend(),   // lets the model "see" screenshots
                    audioBackend = Backend.CPU(),    // lets the model "hear" voice
                    // Context window (how much text the model can remember in one task).
                    // If Android Studio says "No parameter named maxNumTokens", delete this line.
                    maxNumTokens = 8192,
                    cacheDir = ctx.cacheDir.path,
                )
                val e = Engine(config)
                e.initialize()
                engine = e
                backendName = name
                lastError = null
                return e
            } catch (t: Throwable) {
                last = t
            }
        }
        lastError = last?.message
        throw IllegalStateException("Could not load the model: ${last?.message}", last)
    }
}
