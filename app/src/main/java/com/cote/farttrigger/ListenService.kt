package com.cote.farttrigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Shared state so the screen can show what the service is doing. */
object ListenState {
    @Volatile var running = false
    var status = "Off"
    var heard = ""
    var onChange: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun update(status: String? = null, heard: String? = null) {
        status?.let { this.status = it }
        heard?.let { this.heard = it }
        main.post { onChange?.invoke() }
    }
}

/**
 * Foreground service that keeps the microphone open (even with the screen off),
 * listens for the trigger word offline, and plays a fart when it hears it.
 */
class ListenService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STOP = "com.cote.farttrigger.STOP"
        const val ACTION_RELOAD = "com.cote.farttrigger.RELOAD"
        private const val CHANNEL = "listening"
        private const val NOTIF_ID = 42
        private const val SAMPLE_RATE = 16000f
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var player: FartPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var trigger = ""
    private var partialCount = 0
    private var fartCount = 0
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RELOAD -> { if (model != null) startRecognizer(); return START_NOT_STICKY }
        }
        if (!goForeground("Starting…")) { stopSelf(); return START_NOT_STICKY }
        if (player == null) player = FartPlayer(this)
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FartTrigger:listen").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        ListenState.running = true
        if (model == null) loadModel() else startRecognizer()
        return START_NOT_STICKY
    }

    private fun loadModel() {
        val path = ModelManager.modelPath(this)
        if (path == null) {
            ListenState.update(status = "Speech model missing – turn listening off and on again")
            stopSelf()
            return
        }
        ListenState.update(status = "Loading speech model…")
        Thread {
            try {
                val m = Model(path)
                main.post {
                    if (stopped) { m.close(); return@post }
                    model = m
                    startRecognizer()
                }
            } catch (e: Exception) {
                ListenState.update(status = "Couldn't load speech model: ${e.message}")
                main.post { stopSelf() }
            }
        }.start()
    }

    private fun startRecognizer() {
        val m = model ?: return
        speech?.let { it.stop(); it.shutdown() }
        speech = null
        recognizer?.close()
        recognizer = null
        trigger = Words.normalize(Prefs.trigger(this))
        if (trigger.isEmpty()) {
            ListenState.update(status = "No trigger word set")
            stopSelf()
            return
        }
        partialCount = 0
        try {
            // Restricting the vocabulary to just the trigger word (plus "unknown")
            // makes detection fast, accurate and light on battery.
            val grammar = JSONArray().put(trigger).put("[unk]").toString()
            val rec = Recognizer(m, SAMPLE_RATE, grammar)
            recognizer = rec
            speech = SpeechService(rec, SAMPLE_RATE).also { it.startListening(this) }
            val msg = "Listening for \"$trigger\""
            ListenState.update(status = msg)
            goForeground(msg)
        } catch (e: Exception) {
            ListenState.update(status = "Microphone error: ${e.message}")
            stopSelf()
        }
    }

    private fun fart() {
        player?.play()
        fartCount++
        ListenState.update(status = "Listening for \"$trigger\" – $fartCount 💨 so far")
    }

    // ---- Vosk callbacks (delivered on the main thread) ----

    override fun onPartialResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("partial") } ?: return
        val c = Words.count(text, trigger)
        if (c > partialCount) fart()
        partialCount = maxOf(partialCount, c)
    }

    override fun onResult(hypothesis: String?) = finish(hypothesis)
    override fun onFinalResult(hypothesis: String?) = finish(hypothesis)

    private fun finish(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") } ?: ""
        val c = Words.count(text, trigger)
        if (c > partialCount) fart()   // caught it only at the end of the phrase
        partialCount = 0
        if (text.isNotBlank()) ListenState.update(heard = text)
    }

    override fun onError(e: Exception?) {
        ListenState.update(status = "Mic hiccup – restarting…")
        main.postDelayed({ if (!stopped) startRecognizer() }, 2000)
    }

    override fun onTimeout() {
        if (!stopped) startRecognizer()
    }

    // ---- Notification ----

    /** Returns false if Android refused to let us run in the foreground. */
    private fun goForeground(text: String): Boolean = try {
        showNotification(text)
        true
    } catch (e: Exception) {
        ListenState.update(status = "Android blocked listening – open the app and switch it on")
        false
    }

    private fun showNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Fart Trigger is listening 👂")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        speech?.let { it.stop(); it.shutdown() }
        speech = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        player?.release()
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ListenState.running = false
        ListenState.update(status = "Off")
        super.onDestroy()
    }
}
