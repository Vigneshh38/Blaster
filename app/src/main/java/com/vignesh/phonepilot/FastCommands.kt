package com.vignesh.phonepilot

import org.json.JSONObject

/**
 * Instant path for simple, clear commands ("call Umer", "alarm at 6:30 am", "flashlight on").
 * These skip the AI completely, so they run in under a second and can't be misunderstood.
 * Anything more complex (or multi-step, with "and"/"then") goes to the AI.
 */
object FastCommands {

    // "hey can you pls ..." / "... please" → removed, so only the real command is matched.
    private val FILLER_START = Regex("^(?:(?:hey|hi|ok|okay|please|pls|plz|kindly|just|can you|could you|will you|would you|i want you to)\\b[ ,]*)+", RegexOption.IGNORE_CASE)
    private val FILLER_END = Regex("(?:[ ,]+(?:please|pls|plz|now|for me|quickly))+$", RegexOption.IGNORE_CASE)

    fun match(raw: String): Pair<String, JSONObject>? {
        // Keep the original letters (for message text), and a lowercase copy for matching commands.
        val orig = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
            .replace(FILLER_START, "").replace(FILLER_END, "").trim()
        val s = orig.lowercase()
        // WhatsApp / SMS messages (checked first: the message text itself may contain "and").
        matchMessage(orig)?.let { return it }

        if (Regex("\\b(and|then|after that)\\b").containsMatchIn(s)) return null // multi-step → AI

        // call <name or number>
        Regex("^(?:call|phone|dial|ring)(?: to)? (.+)$").find(s)?.let {
            return "phone_call" to JSONObject().put("name_or_number", it.groupValues[1])
        }

        // alarm at 6, alarm for 6:30 am, wake me up at 7 pm
        Regex("(?:alarm|wake me(?: up)?)(?: for| at)? (\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm|a\\.m|p\\.m)?").find(s)?.let {
            var h = it.groupValues[1].toInt()
            val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = it.groupValues[3]
            if (ap.startsWith("p") && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            if (h in 0..23 && m in 0..59) return "set_alarm" to JSONObject().put("hour", h).put("minute", m)
        }

        // timer for 5 minutes
        Regex("timer (?:for )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)").find(s)?.let {
            val n = it.groupValues[1].toInt()
            val unit = it.groupValues[2]
            val secs = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            return "set_timer" to JSONObject().put("seconds", secs)
        }

        // flashlight / torch on|off
        if (Regex("\\b(flash ?light|torch)\\b").containsMatchIn(s)) {
            val on = when {
                Regex("\\boff\\b").containsMatchIn(s) -> false
                Regex("\\bon\\b").containsMatchIn(s) -> true
                else -> null
            }
            if (on != null) return "flashlight" to JSONObject().put("on", on)
        }

        // volume to 40%
        Regex("volume (?:to )?(\\d{1,3}) ?(?:%|percent)?$").find(s)?.let {
            return "set_volume" to JSONObject().put("percent", it.groupValues[1].toInt()).put("stream", "media")
        }

        // play / pause / next song
        Regex("^(play|pause|resume|stop)(?: the)?(?: music| song| video)?$").find(s)?.let {
            val a = if (it.groupValues[1] == "pause" || it.groupValues[1] == "stop") "pause" else "play"
            return "media_control" to JSONObject().put("action", a)
        }
        Regex("^(next|previous) (?:song|track|video)$").find(s)?.let {
            return "media_control" to JSONObject().put("action", it.groupValues[1])
        }

        // battery / time / date
        if (Regex("\\b(battery|what time|what's the time|today's date|what day)\\b").containsMatchIn(s)) {
            return "device_info" to JSONObject()
        }

        // open <app>
        Regex("^(?:open|launch|start) (?:the )?(.+?)(?: app)?$").find(s)?.let {
            return "open_app" to JSONObject().put("name", it.groupValues[1])
        }
        return null
    }

    private fun clean(t: String) = t.trim().trim('"', '\'', ',', ':').trim().removePrefix("my ").removePrefix("My ")

    /** "send hi to umer on whatsapp", "whatsapp umer saying I'm late", "text amma on whatsapp: ok" */
    private fun matchMessage(s: String): Pair<String, JSONObject>? {
        fun wa(contact: String, msg: String) = "whatsapp_message" to
            JSONObject().put("name_or_number", clean(contact)).put("message", clean(msg))
        fun sms(contact: String, msg: String) = "send_sms" to
            JSONObject().put("name_or_number", clean(contact)).put("message", clean(msg))
        val via = "(?:on|in|via|through) (?:whatsapp|wa)"
        val say = "(?:saying|that|as|with|message|:)"

        // send <msg> to <contact> on whatsapp
        Regex("^(?:send|message|text) (.+) to (.+?) $via$", RegexOption.IGNORE_CASE).find(s)?.let { return wa(it.groupValues[2], it.groupValues[1]) }
        // (send a) whatsapp (message) to <contact> saying <msg>
        Regex("^(?:send )?(?:an? )?(?:whatsapp|wa)(?: message| msg| text)? (?:to )?(.+?) $say (.+)$", RegexOption.IGNORE_CASE).find(s)?.let { return wa(it.groupValues[1], it.groupValues[2]) }
        // message/text <contact> on whatsapp (saying) <msg>
        Regex("^(?:send )?(?:an? )?(?:message|msg|text)(?: to)? (.+?) $via(?: $say)? (.+)$", RegexOption.IGNORE_CASE).find(s)?.let { return wa(it.groupValues[1], it.groupValues[2]) }
        // send an sms to <contact> saying <msg>
        Regex("^(?:send )?(?:an? )?(?:sms|text message) (?:to )?(.+?) $say (.+)$", RegexOption.IGNORE_CASE).find(s)?.let { return sms(it.groupValues[1], it.groupValues[2]) }
        return null
    }
}
