package com.vignesh.phonepilot

import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** A tool description the model reads. We run the tool ourselves (manual tool calling). */
class SchemaTool(private val json: String) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = json
    override fun execute(paramsJsonString: String): String = "{}" // not used: we execute tools in Tools.execute()
}

/** Result of one tool call, sent back to the model. */
class Outcome(val ok: Boolean, val result: String, val screen: String? = null, val image: ByteArray? = null) {
    fun json(): String = JSONObject().apply {
        put("ok", ok)
        put("result", result)
        if (screen != null) put("new_screen", screen)
        if (image != null) put("note", "A screenshot image is attached.")
    }.toString()
}

private class P(val name: String, val type: String, val desc: String, val required: Boolean = true, val options: List<String>? = null)

/** All tools the model can use, plus the code that runs them. */
class Tools(private val svc: AgentService) {

    private val phone = PhoneActions(svc)
    private var snapshot: ScreenSnapshot? = null

    /** Keywords that mean "this action cannot be undone" — we ask the user first. */
    private val sensitive = Regex(
        "\\b(pay|payment|send|delete|remove|buy|order|purchase|checkout|transfer|uninstall|post|publish|reset|erase|format|block|unfriend|unfollow|confirm)\\b",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------------ schemas

    val schemas: List<String> = listOf(
        spec("read_screen", "Read the current screen again (use after waiting or if unsure)."),
        spec("look_at_screen", "Take a screenshot so you can SEE the screen. Use when elements have no text (games, maps, photos, icons)."),
        spec("tap", "Tap a screen element by its id from the most recent screen.",
            P("element_id", "integer", "The [id] of the element")),
        spec("long_press", "Long-press a screen element by its id.",
            P("element_id", "integer", "The [id] of the element")),
        spec("tap_position", "Tap a point on the screen by percent position (use only with look_at_screen).",
            P("x_percent", "number", "0 = left edge, 100 = right edge"),
            P("y_percent", "number", "0 = top edge, 100 = bottom edge")),
        spec("type_text", "Type text into a text box (Input element).",
            P("element_id", "integer", "The [id] of the Input element; use -1 for the box that is already focused"),
            P("text", "string", "The exact text to type"),
            P("press_enter", "boolean", "true to press Enter/Search after typing. Default false", required = false)),
        spec("scroll", "Scroll to see more content.",
            P("direction", "string", "down = see content below, up = see content above", options = listOf("down", "up", "left", "right")),
            P("element_id", "integer", "Optional id of the list to scroll; -1 for the whole screen", required = false)),
        spec("swipe", "Swipe from one point to another, in screen percent.",
            P("start_x", "number", "start x %"), P("start_y", "number", "start y %"),
            P("end_x", "number", "end x %"), P("end_y", "number", "end y %")),
        spec("press_key", "Press a system button.",
            P("key", "string", "Which button", options = listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot"))),
        spec("wait", "Wait for the screen to load.",
            P("seconds", "integer", "1 to 10")),
        spec("open_app", "Open an installed app by its name.",
            P("name", "string", "App name as the user said it, e.g. WhatsApp, YouTube, Camera")),
        spec("set_alarm", "Set an alarm.",
            P("hour", "integer", "0-23 (24-hour clock)"), P("minute", "integer", "0-59"),
            P("label", "string", "Optional alarm label", required = false)),
        spec("set_timer", "Start a countdown timer.",
            P("seconds", "integer", "Length in seconds"),
            P("label", "string", "Optional label", required = false)),
        spec("call_contact", "Phone call a contact or number.",
            P("name_or_number", "string", "Contact name exactly as the user said, or a phone number")),
        spec("send_sms", "Send a normal SMS text message.",
            P("name_or_number", "string", "Contact name or number"),
            P("message", "string", "The exact message text")),
        spec("whatsapp_message", "Open a WhatsApp chat with the message typed. After this, tap the Send button.",
            P("name_or_number", "string", "Contact name or number"),
            P("message", "string", "The exact message text")),
        spec("flashlight", "Turn the flashlight on or off.",
            P("on", "boolean", "true = on, false = off")),
        spec("set_volume", "Set the volume.",
            P("percent", "integer", "0-100"),
            P("stream", "string", "Which volume", options = listOf("media", "ring", "alarm", "notification"))),
        spec("media_control", "Control music or video playing.",
            P("action", "string", "What to do", options = listOf("play_pause", "play", "pause", "next", "previous"))),
        spec("open_settings", "Open a settings page. Then change the setting using the screen.",
            P("page", "string", "e.g. wifi, bluetooth, display, sound, battery, location, airplane, mobile data, hotspot")),
        spec("open_url", "Open a website link.", P("url", "string", "The web address")),
        spec("navigate_to", "Start map navigation to a place.", P("destination", "string", "Place or address")),
        spec("device_info", "Get the current time, date and battery level."),
        spec("ask_user", "Ask the user a question when the command is unclear or a choice is needed. Returns their answer.",
            P("question", "string", "Short, clear question")),
        spec("finish", "Call when the task is done (or impossible). The summary is spoken to the user.",
            P("summary", "string", "One or two short sentences: what you did, or the answer to their question")),
    )

    // ------------------------------------------------------------------ screen

    fun freshScreen(): String {
        val s = ScreenReader.capture(svc)
        snapshot = s
        return s.text
    }

    private fun element(args: JSONObject): UiElement? {
        val id = args.optInt("element_id", -1)
        val el = snapshot?.elements?.getOrNull(id) ?: return null
        return if (el.node.refresh()) el else null
    }

    private suspend fun afterAction(ok: Boolean, result: String): Outcome {
        UiActions.waitForIdle(svc)
        return Outcome(ok, result, freshScreen())
    }

    private suspend fun guard(what: String): Boolean {
        if (!ModelManager.askBeforeSensitive(svc)) return true
        if (!sensitive.containsMatchIn(what) && !what.startsWith("Call ") && !what.startsWith("Send SMS")) return true
        return svc.overlay.confirm(what)
    }

    // ------------------------------------------------------------------ execute

    suspend fun execute(name: String, a: JSONObject): Outcome { return try {
        when (name) {
            "read_screen" -> Outcome(true, "Current screen read.", freshScreen())

            "look_at_screen" -> {
                val img = UiActions.screenshotJpeg(svc)
                if (img == null) Outcome(false, "Screenshot failed.", freshScreen())
                else Outcome(true, "Screenshot taken.", freshScreen(), img)
            }

            "tap" -> {
                val el = element(a) ?: return Outcome(false, "Element id not found on the current screen. Use an id from the newest screen.", freshScreen())
                if (!guard("Tap \"${el.label}\"")) return Outcome(false, "The user said NO to this action. Do not do it. Call finish.")
                afterAction(UiActions.tap(svc, el), "Tapped [${a.optInt("element_id")}] \"${el.label}\".")
            }

            "long_press" -> {
                val el = element(a) ?: return Outcome(false, "Element id not found.", freshScreen())
                afterAction(UiActions.longPress(svc, el), "Long-pressed \"${el.label}\".")
            }

            "tap_position" -> {
                val x = a.optDouble("x_percent", -1.0); val y = a.optDouble("y_percent", -1.0)
                if (x !in 0.0..100.0 || y !in 0.0..100.0) return Outcome(false, "x_percent and y_percent must be 0-100.")
                afterAction(UiActions.tapPercent(svc, x, y), "Tapped at ($x%, $y%).")
            }

            "type_text" -> {
                val text = a.optString("text")
                val el = element(a)
                val msg = UiActions.typeText(svc, el, text, a.optBoolean("press_enter", false))
                afterAction(!msg.startsWith("No") && !msg.startsWith("Could"), msg)
            }

            "scroll" -> {
                val dir = a.optString("direction", "down")
                afterAction(UiActions.scroll(svc, dir, element(a)), "Scrolled $dir.")
            }

            "swipe" -> afterAction(
                UiActions.swipePercent(svc, a.optDouble("start_x"), a.optDouble("start_y"), a.optDouble("end_x"), a.optDouble("end_y")),
                "Swiped."
            )

            "press_key" -> {
                val key = a.optString("key")
                if (key == "lock_screen" && !guard("Lock the screen")) return Outcome(false, "User said no.")
                afterAction(UiActions.pressKey(svc, key), "Pressed $key.")
            }

            "wait" -> {
                delay(a.optInt("seconds", 2).coerceIn(1, 10) * 1000L)
                Outcome(true, "Waited.", freshScreen())
            }

            "open_app" -> {
                val r = phone.openApp(a.optString("name"))
                if (r.startsWith("ERROR")) Outcome(false, r) else { delay(1200); afterAction(true, r) }
            }

            "set_alarm" -> Outcome(true, phone.setAlarm(a.optInt("hour"), a.optInt("minute"), a.optString("label").ifBlank { null }))
            "set_timer" -> Outcome(true, phone.setTimer(a.optInt("seconds"), a.optString("label").ifBlank { null }))

            "call_contact" -> {
                val (c, err) = phone.resolveContact(a.optString("name_or_number"))
                if (c == null) Outcome(false, err!!)
                else if (!guard("Call ${c.name} (${c.number})")) Outcome(false, "The user said NO. Do not call. Call finish.")
                else {
                    phone.call(c)
                    // Verify the call really started (it may be waiting for a SIM choice on screen).
                    var started = phone.isInCall()
                    var waited = 0
                    while (started == false && waited < 4000) { delay(500); waited += 500; started = phone.isInCall() }
                    when (started) {
                        true -> Outcome(true, "Call to ${c.name} (${c.number}) has started.")
                        false -> afterAction(false, "The call to ${c.name} did NOT start yet. Look at the new screen: if it asks which SIM to use, tap a SIM. If not, tell the user the call failed.")
                        null -> afterAction(true, "Call requested to ${c.name} (${c.number}). Check the new screen shows a call in progress before saying it worked.")
                    }
                }
            }

            "send_sms" -> {
                val (c, err) = phone.resolveContact(a.optString("name_or_number"))
                val msg = a.optString("message")
                if (c == null) Outcome(false, err!!)
                else if (!guard("Send SMS to ${c.name}: \"$msg\"")) Outcome(false, "The user said NO. Do not send. Call finish.")
                else Outcome(true, phone.sendSms(c, msg))
            }

            "whatsapp_message" -> {
                val (c, err) = phone.resolveContact(a.optString("name_or_number"))
                if (c == null) Outcome(false, err!!)
                else { val r = phone.whatsappMessage(c, a.optString("message")); delay(2000); afterAction(true, r) }
            }

            "flashlight" -> Outcome(true, phone.flashlight(a.optBoolean("on", true)))
            "set_volume" -> Outcome(true, phone.setVolume(a.optInt("percent", 50), a.optString("stream", "media")))
            "media_control" -> Outcome(true, phone.media(a.optString("action", "play_pause")))
            "open_settings" -> { val r = phone.openSettings(a.optString("page")); delay(800); afterAction(true, r) }
            "open_url" -> { val r = phone.openUrl(a.optString("url")); delay(1500); afterAction(true, r) }
            "navigate_to" -> { val r = phone.navigateTo(a.optString("destination")); delay(1500); afterAction(true, r) }
            "device_info" -> Outcome(true, phone.deviceInfo())

            "ask_user" -> {
                val answer = svc.overlay.ask(a.optString("question"))
                if (answer.isNullOrBlank()) Outcome(false, "The user did not answer. Call finish and say what you need.")
                else Outcome(true, "User answered: \"$answer\"")
            }

            else -> Outcome(false, "Unknown tool \"$name\".")
        }
    } catch (e: SecurityException) {
        Outcome(false, "Permission missing for $name. Tell the user to open PhonePilot and tap Grant permissions.")
    } catch (e: Exception) {
        Outcome(false, "Failed: ${e.message}")
    } }

    // ------------------------------------------------------------------ helpers

    private fun spec(name: String, description: String, vararg params: P): String {
        val props = JSONObject()
        val required = JSONArray()
        for (p in params) {
            val o = JSONObject().put("type", p.type).put("description", p.desc)
            p.options?.let { o.put("enum", JSONArray(it)) }
            props.put(p.name, o)
            if (p.required) required.put(p.name)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", required))
            .toString()
    }
}
