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
You are PhonePilot, an assistant that fully controls the user's Android phone (a OnePlus 15) by calling tools. You run offline on the phone.

How to work, step by step:
1. Understand the command exactly: which app, which person, which words, which setting, what time. Use the user's exact names and text. Never invent or change them.
2. If a direct tool fits, use it first: open_app, set_alarm, set_timer, call_contact, send_sms, whatsapp_message, flashlight, set_volume, media_control, open_settings, open_url, navigate_to, device_info. They are faster and more exact than tapping.
3. Otherwise use the screen like a person: tap, type_text, scroll, swipe, press_key. Every screen element has an [id]. Only use ids from the NEWEST screen, because ids change after every action.
4. Call exactly ONE tool at a time. After each action you get "new_screen". Check it changed as you expected before the next step. If the thing you need is not visible, scroll. If you are stuck, press back, try another way, or use look_at_screen to see the screen as an image.
5. If something important is unclear or several choices fit (for example two contacts), call ask_user. Do not guess.
6. When the task is finished, call finish with a one-sentence summary. If it is impossible, call finish and say why. If the user only asked a question, answer it with finish.
7. Time is 24-hour for set_alarm: "6:30 pm" means hour=18, minute=30.

Examples:
- "wake me up at 6 tomorrow" -> set_alarm(hour=6, minute=0) -> finish
- "turn on wifi" -> open_settings(page="wifi") -> tap the Wi-Fi switch that is OFF -> finish
- "message Amma on WhatsApp I'll be late" -> whatsapp_message(name_or_number="Amma", message="I'll be late") -> tap the Send button -> finish
- "search lo-fi music on YouTube" -> open_app(name="YouTube") -> tap the Search button -> type_text(text="lo-fi music", press_enter=true) -> finish
""".trimIndent()
    }

    private val tools = Tools(svc)
    private val actionLog = mutableListOf<String>()
    private var commandText = ""

    suspend fun run(command: String?, audio: ByteArray?) {
        val ui = svc.overlay
        ui.setRunning(true)
        var conv: Conversation? = null
        try {
            ui.status("Loading brain…")
            val engine = Brain.ensureLoaded(svc)
            commandText = command ?: "(spoken command in audio)"
            ui.status("Understanding: ${command ?: "voice command"}")

            conv = newConversation(engine)
            var reply = send(conv, firstMessage(command, audio))
            var steps = 0

            while (currentCoroutineContext().isActive) {
                val calls = reply.toolCalls
                if (calls.isEmpty()) {
                    // The model answered in plain text instead of a tool: treat as final answer.
                    done(reply.toString().ifBlank { "Done." })
                    return
                }
                val call = calls.first()
                val args = toJson(call.arguments)
                if (call.name == "finish") {
                    done(args.optString("summary", "Done."))
                    return
                }
                steps++
                if (steps > MAX_STEPS) {
                    done("I stopped after $MAX_STEPS steps without finishing. Please try a simpler command.")
                    return
                }
                ui.status("Step $steps: ${describe(call.name, args)}")
                val outcome = tools.execute(call.name, args)
                actionLog += "${call.name}($args) -> ${outcome.result.take(100)}"

                val responses = mutableListOf<Content>(Content.ToolResponse(call.name, outcome.json()))
                // If the model asked for several tools at once, only the first ran.
                for (extra in calls.drop(1)) {
                    responses += Content.ToolResponse(
                        extra.name,
                        JSONObject().put("ok", false)
                            .put("result", "Skipped. Do ONE action at a time; check the new screen first.").toString()
                    )
                }
                val result = sendToolResults(conv, engine, responses, outcome.image)
                conv = result.first
                reply = result.second
            }
        } catch (c: CancellationException) {
            ui.status("Stopped.")
            throw c
        } catch (t: Throwable) {
            ui.status("Error: ${t.message}")
            svc.voice.speak("Sorry, something went wrong.")
        } finally {
            runCatching { conv?.close() }
            ui.setRunning(false)
        }
    }

    // ------------------------------------------------------------------ model I/O

    private suspend fun newConversation(engine: Engine): Conversation = withContext(Brain.dispatcher) {
        val toolList = tools.schemas.map { tool(SchemaTool(it)) }
        val sampler = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2) // low randomness = precise
        try {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_PROMPT),
                    tools = toolList,
                    automaticToolCalling = false,
                    samplerConfig = sampler,
                    // Short private "thinking" before each action improves accuracy.
                    thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 384),
                )
            )
        } catch (t: Throwable) {
            // Model/runtime without thinking support: create without it.
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

    private fun firstMessage(command: String?, audio: ByteArray?): Contents {
        val screen = tools.freshScreen()
        return if (audio != null) {
            Contents.of(
                Content.AudioBytes(audio),
                Content.Text("The user's command is spoken in the audio above. Understand it exactly, then act.\n\nCurrent screen:\n$screen")
            )
        } else {
            Contents.of(Content.Text("User command: \"$command\"\n\nCurrent screen:\n$screen"))
        }
    }

    private suspend fun send(conv: Conversation, contents: Contents): Message =
        withContext(Brain.dispatcher) { conv.sendMessage(contents) }

    /**
     * Sends tool results back. Attaches a screenshot when the model asked to look.
     * If the conversation is too long (memory full), starts a fresh one with a summary.
     */
    private suspend fun sendToolResults(
        conv: Conversation, engine: Engine, responses: List<Content>, image: ByteArray?
    ): Pair<Conversation, Message> = withContext(Brain.dispatcher) {
        try {
            val msg = if (image != null) {
                try {
                    conv.sendMessage(Message.tool(Contents.of(responses + Content.ImageBytes(image))))
                } catch (t: Throwable) {
                    conv.sendMessage(Message.tool(Contents.of(responses)))
                }
            } else {
                conv.sendMessage(Message.tool(Contents.of(responses)))
            }
            conv to msg
        } catch (t: Throwable) {
            // Recover: new conversation that knows what was already done.
            runCatching { conv.close() }
            val fresh = newConversation(engine)
            val recap = "User command: \"$commandText\"\n\nActions already done:\n" +
                actionLog.takeLast(10).joinToString("\n") +
                "\n\nContinue from here. Current screen:\n" + tools.freshScreen()
            fresh to fresh.sendMessage(Contents.of(Content.Text(recap)))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun done(text: String) {
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
        "scroll" -> "scrolling ${a.optString("direction")}"
        "look_at_screen" -> "looking at the screen"
        else -> name.replace('_', ' ')
    }
}
