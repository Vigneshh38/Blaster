# PhonePilot — offline AI that controls your phone

You speak or type a command. PhonePilot reads your screen, decides what to do, and taps, types and scrolls for you. Everything runs **on the phone, with no internet** (only the first model download needs Wi-Fi).

## How it works

```
You: "Message Amma on WhatsApp I'll be late"
        │
        ▼
 Ears ── on-device speech-to-text (or Gemma listens to the audio itself)
        │
        ▼
 Brain ── Gemma 4 E4B model, running on the phone's GPU
        │   reads the screen as a numbered list:  [7] Button "Send" (tap)
        │   picks ONE tool:  tap(element_id = 7)
        ▼
 Hands ── Accessibility Service taps / types / scrolls
        │
        ▼
 Checks the new screen → next step → … → finish → speaks the result
```

**Words used here**
- **Model** – the AI "brain" file (3.7 GB) that understands your sentence.
- **Tool / function calling** – the model does not type free text; it picks from a fixed list of actions (tap, open_app, set_alarm…). This keeps it exact.
- **Accessibility Service** – an Android feature that lets an app read the screen and press buttons for you (screen readers for blind users use it).
- **GPU** – the graphics chip. It runs the model ~5× faster than the normal processor (CPU).

## Why Gemma 4 E4B (the "best model" for your phone)

| Model | Fits on a phone? | Sees screen | Hears voice | Tool calling |
|---|---|---|---|---|
| **Gemma 4 E4B** ✅ chosen | Yes (~1 GB GPU memory) | Yes | Yes | Yes, built-in |
| Gemma 4 E2B | Yes (faster, less smart) | Yes | Yes | Yes |
| Gemma 4 12B / 26B | No – needs a laptop GPU | – | – | – |

E4B is the most accurate model Google ships for Android phones today, and it supports tool calling with **constrained decoding** (the runtime forces the model's output into a valid tool call, so it can't produce broken commands).

Extra precision features in the code:
- Low "temperature" (0.2) → the model picks the most likely answer instead of being creative.
- Short private "thinking" before each action.
- One action at a time, then it checks the new screen.
- Direct tools (alarm, call, SMS, WhatsApp, flashlight, volume, settings…) instead of tapping when possible.
- Asks you when a name matches two contacts, and asks **Yes/No before sending, paying, deleting or calling** (switch in the app).

## Build it (Android Studio)

1. Install **Android Studio** (latest) on your laptop.
2. **File → Open** → select this `PhonePilot` folder. Wait for "Gradle sync" to finish (it downloads libraries; needs internet once).
   - If it offers to upgrade the Android Gradle Plugin, click **Upgrade**.
3. On the OnePlus 15: **Settings → About device → tap "Build number" 7 times** → go back → **Developer options → USB debugging ON**.
4. Connect the phone with a USB cable, pick it at the top of Android Studio, press **Run ▶**.

If the build shows `No parameter with name 'maxNumTokens'`, delete that one line in `Brain.kt` and run again.

## First-time setup on the phone (in the PhonePilot app)

1. **Download the brain** (3.7 GB, use Wi-Fi). Or download `gemma-4-E4B-it.litertlm` from [Hugging Face](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) on your laptop, copy it to the phone, and use "pick a model file".
2. **Give permissions** (mic, calls, SMS, contacts).
3. **Allow restricted settings**: Android blocks accessibility for apps not from the Play Store. App info → ⋮ (top right) → *Allow restricted settings*.
4. **Turn on PhonePilot** in Settings → Accessibility → Installed apps.
5. For fully offline voice: Settings → search **"On-device speech recognition"** (Google app) → download **English (India)**.

Now a blue 🎙 bubble floats over every app:
- **Long-press** it → speak a command
- **Tap** it → type a command
- While working it turns **red ■** → tap to stop

## Commands to try
- "Set an alarm for 6:30 am"
- "Turn on the flashlight" / "Set media volume to 40 percent"
- "Call Rahul" (asks Yes/No first)
- "Open YouTube and search lo-fi music"
- "Send a WhatsApp message to Amma saying I'll be late"
- "Turn on Wi-Fi" / "Turn on dark mode"
- "Open Instagram and like the first post"
- "What's my battery?"

## Honest limits
- Speed: about 1–3 seconds per step. A 5-step task takes ~10–15 s.
- Banking/UPI apps (GPay, PhonePe, bank apps) usually **block** accessibility control on purpose. That is a security feature, not a bug.
- Games and apps drawn without text (maps, some camera screens) need `look_at_screen` (vision) and are less reliable.
- Long tasks across many apps can still go wrong. Even the best large cloud models finish ~60–80% of hard phone tasks in research tests, so check important results.
- It cannot unlock the phone; the screen must be unlocked.
- "Speak to Gemma directly" (the model listens to raw audio) works only from the app screen, because Android only allows the mic while the app is open.

## Files
| File | What it does |
|---|---|
| `Brain.kt` | Loads Gemma 4 E4B on GPU (falls back to CPU) |
| `Agent.kt` | The step-by-step loop + instructions given to the model |
| `Tools.kt` | List of 25 actions the model can choose, and runs them |
| `ScreenReader.kt` | Turns the screen into a numbered list for the model |
| `UiActions.kt` | Tap, type, scroll, swipe, keys, screenshot |
| `PhoneActions.kt` | Direct actions: apps, alarm, timer, call, SMS, WhatsApp, flashlight, volume, settings, maps |
| `Overlay.kt` | Floating 🎙 bubble, status line, Yes/No panel |
| `Voice.kt` | Offline speech-to-text, text-to-speech, WAV recorder |
| `AgentService.kt` | The Accessibility Service that ties it together |
| `MainActivity.kt` | Setup screen |
| `ModelManager.kt` | Downloads / stores the model file |
