package com.vignesh.phonepilot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Direct actions that skip tapping through screens.
 * They are faster and more exact than UI taps, so the model is told to prefer them.
 */
class PhoneActions(private val ctx: Context) {

    data class Contact(val name: String, val number: String)

    // ---------- apps ----------

    fun openApp(name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }.distinctBy { it.second }
        val q = name.trim().lowercase()
        val match = apps.firstOrNull { it.first.lowercase() == q }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.first.lowercase().contains(q) }
            ?: apps.minByOrNull { editDistance(it.first.lowercase(), q) }
                ?.takeIf { editDistance(it.first.lowercase(), q) <= maxOf(2, q.length / 4) }
        if (match == null) {
            val close = apps.sortedBy { editDistance(it.first.lowercase(), q) }.take(5).joinToString { it.first }
            return "ERROR: No app called \"$name\". Closest installed apps: $close"
        }
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return "ERROR: ${match.first} cannot be opened."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(intent)
        return "Opened ${match.first}."
    }

    // ---------- clock ----------

    fun setAlarm(hour: Int, minute: Int, label: String?): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        ctx.startActivity(i)
        return "Alarm set for %02d:%02d%s.".format(hour, minute, if (label.isNullOrBlank()) "" else " ($label)")
    }

    fun setTimer(seconds: Int, label: String?): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        ctx.startActivity(i)
        return "Timer started for $seconds seconds."
    }

    // ---------- contacts, calls, messages ----------

    /** Finds contacts by name. Returns all matches so the model can ask if more than one fits. */
    fun findContacts(nameOrNumber: String): List<Contact> {
        val digits = nameOrNumber.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 6 && nameOrNumber.none { it.isLetter() }) {
            return listOf(Contact(nameOrNumber, digits))
        }
        val out = mutableListOf<Contact>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${nameOrNumber.trim()}%"),
                null
            )?.use { c ->
                while (c.moveToNext()) out += Contact(c.getString(0), c.getString(1).replace(" ", ""))
            }
        } catch (_: SecurityException) { }
        // Exact name matches first, remove duplicate numbers.
        return out.distinctBy { it.number.takeLast(10) }
            .sortedBy { if (it.name.equals(nameOrNumber.trim(), true)) 0 else 1 }
    }

    /** Picks one contact or explains the problem. */
    fun resolveContact(nameOrNumber: String): Pair<Contact?, String?> {
        val list = findContacts(nameOrNumber)
        return when {
            list.isEmpty() -> null to "ERROR: No contact matches \"$nameOrNumber\". Ask the user for the exact name or number."
            list.size == 1 || list[0].name.equals(nameOrNumber.trim(), true) && list.count { it.name.equals(list[0].name, true) } == 1 ->
                list[0] to null
            else -> null to "AMBIGUOUS: Several contacts match: " +
                list.take(6).joinToString { "${it.name} (${it.number})" } + ". Call ask_user to ask which one."
        }
    }

    fun call(contact: Contact): String {
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Calling ${contact.name} (${contact.number})."
    }

    fun sendSms(contact: Contact, message: String): String {
        val sms = ctx.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(message)
        sms.sendMultipartTextMessage(contact.number, null, parts, null, null)
        return "SMS sent to ${contact.name}: \"$message\""
    }

    /** Opens the WhatsApp chat with the message already typed. The Send button still has to be tapped. */
    fun whatsappMessage(contact: Contact, message: String): String {
        var num = contact.number.filter { it.isDigit() }
        if (num.length == 10) num = "91$num"            // India country code
        if (num.length == 11 && num.startsWith("0")) num = "91${num.drop(1)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=${Uri.encode(message)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isInstalled("com.whatsapp")) i.setPackage("com.whatsapp")
        ctx.startActivity(i)
        return "Opened WhatsApp chat with ${contact.name} with the message typed. Now tap the Send button on the screen to send it."
    }

    // ---------- device controls ----------

    fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "ERROR: No flashlight found."
        cm.setTorchMode(id, on)
        return "Flashlight turned ${if (on) "on" else "off"}."
    }

    fun setVolume(percent: Int, stream: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val type = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(type)
        val v = (max * percent.coerceIn(0, 100) / 100.0).toInt()
        am.setStreamVolume(type, v, AudioManager.FLAG_SHOW_UI)
        return "Set $stream volume to $percent%."
    }

    fun media(action: String): String {
        val code = when (action) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Media: $action."
    }

    fun openSettings(page: String): String {
        val action = when (page.lowercase()) {
            "wifi", "wi-fi" -> Settings.Panel.ACTION_WIFI
            "internet", "mobile data", "data" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "volume", "sound panel" -> Settings.Panel.ACTION_VOLUME
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness", "dark mode" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "ringtone" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane", "airplane mode", "flight mode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "notifications" -> Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
            "hotspot", "network" -> Settings.ACTION_WIRELESS_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date", "time" -> Settings.ACTION_DATE_SETTINGS
            "language", "keyboard" -> Settings.ACTION_LOCALE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "security", "lock screen" -> Settings.ACTION_SECURITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened $page settings. Use the screen to change the setting."
    }

    fun openUrl(url: String): String {
        val u = if (url.startsWith("http")) url else "https://$url"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened $u."
    }

    fun navigateTo(destination: String): String {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i); "Started navigation to $destination."
        } catch (_: Exception) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened map for $destination."
        }
    }

    fun deviceInfo(): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        return "Time: $now. Battery: $battery%${if (charging) " (charging)" else ""}."
    }

    // ---------- helpers ----------

    private fun isInstalled(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
