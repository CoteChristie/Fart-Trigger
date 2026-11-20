# Fart Trigger 💨

An Android app. Type a trigger word, turn on **Listening**, and the phone plays a fart every time it hears that word, even with the screen off.

- Speech recognition runs **offline on the phone** (Vosk). It downloads a 40 MB English model once, the first time you switch listening on.
- It only listens for your word, which keeps it fast and easy on the battery.
- It comes with 5 random built-in farts, or you can pick your own sound file.
- A notification stays up while it's listening. Tap **Stop** there or flip the switch to turn it off.

## Get the APK (no Android Studio needed)

1. Create a new empty repository on github.com and upload everything in this folder, including the hidden `.github` folder.
2. Open the **Actions** tab. The "Build APK" job runs on its own and takes about 5 minutes.
3. Open the finished run and download **FartTrigger-apk** from *Artifacts*. Unzip it on your phone and tap the `.apk` to install. Allow "install unknown apps" if Android asks.

## Or build it on your PC

Open this folder in Android Studio, let it sync, plug in your phone (with USB debugging on) and press ▶ Run.

## First run

1. Type a word and tap **Save word**. Pick a real English word; made-up words can't be recognized.
2. Flip **Listening** on and allow the microphone and notification permissions.
3. Tap **Let it run with screen off** and choose *Allow*. Without this, some phones (Samsung, Xiaomi, OnePlus) kill the app after a while.
   On Samsung you may also need: Settings → Apps → Fart Trigger → Battery → **Unrestricted**.

## Files

- `ListenService.kt`: the foreground service that keeps the mic open and does the detection
- `MainActivity.kt`: the screen
- `FartPlayer.kt`: plays the sounds
- `ModelManager.kt`: one-time model download
- `res/raw/*.wav`: the built-in farts
