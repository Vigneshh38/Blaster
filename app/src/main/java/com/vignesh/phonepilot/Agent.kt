package com.vignesh.phonepilot

import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The agent loop:  look at screen -> model picks ONE tool -> we run it -> show model the new screen -> repeat.
 */
class Agent(private val svc: AgentService) {

    companion object {
        const val MAX_STEPS = 25

        val SYSTEM_PROMPT = """
You are PhonePilot. You control the user's Android phone (OnePlus 15) by calling tools. You run offline.

Rules:
1. Understand the command exactly: which app, person, words, setting or time. Use the user's exact names and text.
2. If a direct tool fits, use it first: open_app, set_alarm, set_timer, phone_call, send_sms, whatsapp_message, flashlight, set_volume, media_control, open_settings, open_url, navigate_to, device_info.
3. Otherwise use the screen like a person: tap, type_text, scroll, swipe, press_key. Use element ids only from the NEWEST screen.
4. Call ONE tool at a time. After each action you get new_screen. Check it before the next step. If stuck, scroll, press back, or use look_at_screen.
5. If something important is unclear (for example two matching contacts), use ask_user.
6. Never claim success unless a tool result had ok true. If something failed, say so.
7. When done, use finish with one short sentence. For a plain question, answer with finish.
8. set_alarm uses 24-hour time: 6:30 pm is hour 18, minute 30.

Examples:
- "wake me up at 6": use set_alarm with hour 6 and minute 0, then finish.
- "turn on wifi": use open_settings with page wifi, tap the Wi-Fi switch that is OFF, then finish.
- "message Amma on WhatsApp I'll be late": use whatsapp_message, then tap the Send button, then finish.
- "search lo-fi music on YouTube": open_app YouTube, tap Search, type_text "lo-fi music" with press_enter true, then finish.
""".trimIndent()

        /** Gemma tool-call text, e.g.  call:phone_call{name_or_number:<|"|>umer<|"|>}  */
        private val CALL_RE = Regex("""call:(?:call:)*([a-z_]+)\{(.*?)\}(?=\s*(?:<tool_call\||<\|tool_call|$|call:))""", RegexOption.DOT_MATCHES_ALL)
        private val ARG_RE = Regex("""([a-z_]+):(?:<\|"\|>(.*?)<\|"\|>|([^,}]*))""", RegexOption.DOT_MATCHES_ALL)
    }

    /** One tool call from the model. */
    private class Call(val name: String, val args: JSONObject)

    /** Model reply. [repaired] = the runtime could not parse it and we fixed it ourselves. */
    private class Reply(val calls: List<Call>, val text: String, val repaired: Boolean)

    private val tools = Tools(svc)
    private val actionLog = mutableListOf<String>()
    private val runLog = mutableListOf<String>()
    private var commandText = ""
    private val t0 = android.os.SystemClock.elapsedRealtime()

    private fun log(line: String) {
        val secs = (android.os.SystemClock.elapsedRealtime() - t0) / 1000.0
        runLog += "[%.1fs] %s".format(secs, line)
    }

    suspend fun run(command: String?, audio: ByteArray?) {
        val ui = svc.overlay
        ui.setRunning(true)
        var conv: Conversation? = null
        try {
            commandText = command ?: "(spoken command in audio)"
            log("Command: $commandText")

            // 1) Instant path for simple commands (no AI needed).
            var prior: String? = null
            val fast = command?.let { FastCommands.match(it) }
            if (fast != null) {
                ui.status("Doing: ${describe(fast.first, fast.second)}")
                val o = tools.execute(fast.first, fast.second)
                log("FAST ${fast.first}(${fast.second}) -> ok=${o.ok}: ${o.result}")
                if (o.ok) { done(o.result); return }
                prior = "I already tried ${fast.first}(${fast.second}) and it returned: ${o.result}"
            }

            // 2) AI path.
            ui.status("Loading brain…")
            val engine = Brain.ensureLoaded(svc)
            log("Brain ready on ${Brain.backendName}")
            ui.status("Understanding: ${command ?: "voice command"}")

            val c0 = newConversation(engine)
            conv = c0
            val first = firstMessage(command, audio, prior)
            var reply = ask { c0.sendMessage(first) }
            var steps = 0

            while (currentCoroutineContext().isActive) {
                if (reply.calls.isEmpty()) {
                    done(reply.text.ifBlank { "Done." })   // plain-text answer
                    return
                }
                val call = reply.calls.first()
                if (call.name == "finish") {
                    done(call.args.optString("summary", "Done."))
                    return
                }
                steps++
                if (steps > MAX_STEPS) {
                    done("I stopped after $MAX_STEPS steps without finishing. Please try a simpler command.")
                    return
                }
                ui.status("Step $steps: ${describe(call.name, call.args)}")
                val outcome = tools.execute(call.name, call.args)
                actionLog += "${call.name}(${call.args}) -> ok=${outcome.ok}: ${outcome.result.take(100)}"
                log("Step $steps ${call.name}(${call.args}) -> ok=${outcome.ok}: ${outcome.result.take(120)}")

                val current = conv!!
                if (reply.repaired) {
                    // The broken reply is not in the model's history, so continue in a fresh conversation.
                    runCatching { current.close() }
                    val fresh = newConversation(engine)
                    conv = fresh
                    reply = sendRecap(fresh)
                    continue
                }

                val responses = mutableListOf<Content>(Content.ToolResponse(call.name, outcome.json()))
                for (extra in reply.calls.drop(1)) {
                    responses += Content.ToolResponse(
                        extra.name,
                        JSONObject().put("ok", false).put("result", "Skipped. Do ONE action at a time.").toString()
                    )
                }
                reply = try {
                    ask {
                        if (outcome.image != null) {
                            try {
                                current.sendMessage(Message.tool(Contents.of(responses + Content.ImageBytes(outcome.image))))
                            } catch (t: Throwable) {
                                if (isToolParseError(t)) throw t
                                current.sendMessage(Message.tool(Contents.of(responses)))
                            }
                        } else {
                            current.sendMessage(Message.tool(Contents.of(responses)))
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // Memory full or other runtime error: fresh conversation that knows what was done.
                    log("Recovering after: ${t.message?.take(80)}")
                    runCatching { current.close() }
                    val fresh = newConversation(engine)
                    conv = fresh
                    sendRecap(fresh)
                }
            }
        } catch (c: CancellationException) {
            ui.status("Stopped.")
            throw c
        } catch (t: Throwable) {
            log("ERROR: ${t.message}")
            ui.status("Error: ${t.message?.take(120)}")
            svc.voice.speak("Sorry, something went wrong.")
        } finally {
            ModelManager.prefs(svc).edit().putString("last_log", runLog.joinToString("\n")).apply()
            runCatching { conv?.close() }
            ui.setRunning(false)
        }
    }

    // ------------------------------------------------------------------ model I/O

    /**
     * Sends something to the model on the LLM thread.
     * If the runtime fails to parse a slightly broken tool call (e.g. "call:call:phone_call{...}"),
     * we read the call out of the error text ourselves instead of failing.
     */
    private suspend fun ask(block: () -> Message): Reply = withContext(Brain.dispatcher) {
        try {
            val m = block()
            val r = Reply(m.toolCalls.map { Call(it.name, toJson(it.arguments)) }, m.toString(), false)
            log("AI replied: ${r.calls.joinToString { it.name }.ifEmpty { "text" }}")
            r
        } catch (t: Throwable) {
            val repaired = if (isToolParseError(t)) parseCalls(t.message.orEmpty()) else emptyList()
            if (repaired.isEmpty()) throw t
            log("AI reply repaired: ${repaired.joinToString { it.name }}")
            Reply(repaired, "", true)
        }
    }

    private suspend fun sendRecap(conv: Conversation): Reply {
        val recap = "User command: \"$commandText\"\n\nActions already done:\n" +
            actionLog.takeLast(10).joinToString("\n") +
            "\n\nContinue from here. Current screen:\n" + tools.freshScreen()
        return ask { conv.sendMessage(Contents.of(Content.Text(recap))) }
    }

    private fun isToolParseError(t: Throwable) =
        t.message?.contains("parse tool call", ignoreCase = true) == true ||
            t.message?.contains("Failed to parse FC", ignoreCase = true) == true

    private fun parseCalls(text: String): List<Call> {
        val known = tools.schemas.map { JSONObject(it).getString("name") }.toSet()
        return CALL_RE.findAll(text).mapNotNull { m ->
            val name = m.groupValues[1]
            if (name !in known) return@mapNotNull null
            val args = JSONObject()
            ARG_RE.findAll(m.groupValues[2]).forEach { a ->
                val key = a.groupValues[1]
                val str = a.groups[2]?.value
                val raw = a.groups[3]?.value?.trim()
                when {
                    str != null -> args.put(key, str)
                    raw == "true" || raw == "false" -> args.put(key, raw.toBoolean())
                    raw?.toLongOrNull() != null -> args.put(key, raw.toLong())
                    raw?.toDoubleOrNull() != null -> args.put(key, raw.toDouble())
                    !raw.isNullOrEmpty() -> args.put(key, raw)
                }
            }
            Call(name, args)
        }.distinctBy { it.name + it.args }.toList()
    }

    private suspend fun newConversation(engine: Engine): Conversation = withContext(Brain.dispatcher) {
        val toolList = tools.schemas.map { tool(SchemaTool(it)) }
        val sampler = SamplerConfig(topK = 5, topP = 0.95, temperature = 0.2)
        try {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_PROMPT),
                    tools = toolList,
                    automaticToolCalling = false,
                    samplerConfig = sampler,
                    // Thinking OFF: it made every step 10-20 s slower on the phone.
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                )
            )
        } catch (t: Throwable) {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_PROMPT),
                    tools = toolList,
                    automaticToolCalling = false,
                    samplerConfig = sampler,
                )
            )
        }
    }

    private fun firstMessage(command: String?, audio: ByteArray?, prior: String?): Contents {
        val screen = (if (prior != null) "$prior\n\n" else "") + tools.freshScreen()
        return if (audio != null) {
            Contents.of(
                Content.AudioBytes(audio),
                Content.Text("The user's command is spoken in the audio above. Understand it exactly, then act.\n\nCurrent screen:\n$screen")
            )
        } else {
            Contents.of(Content.Text("User command: \"$command\"\n\nCurrent screen:\n$screen"))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun done(text: String) {
        log("DONE: $text")
        svc.overlay.status("✓ $text")
        svc.voice.speak(text)
        svc.addHistory(commandText, text)
    }

    private fun toJson(a: Any?): JSONObject = when (a) {
        null -> JSONObject()
        is JSONObject -> a
        is Map<*, *> -> JSONObject(a.entries.associate { it.key.toString() to it.value })
        is String -> runCatching { JSONObject(a) }.getOrElse { JSONObject() }
        else -> runCatching { JSONObject(a.toString()) }.getOrElse { JSONObject() }
    }

    private fun describe(name: String, a: JSONObject): String = when (name) {
        "tap" -> "tapping item ${a.optInt("element_id")}"
        "type_text" -> "typing \"${a.optString("text")}\""
        "open_app" -> "opening ${a.optString("name")}"
        "phone_call" -> "calling ${a.optString("name_or_number")}"
        "scroll" -> "scrolling ${a.optString("direction")}"
        "look_at_screen" -> "looking at the screen"
        else -> name.replace('_', ' ')
    }
}
