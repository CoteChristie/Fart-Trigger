package com.cote.farttrigger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var triggerInput: EditText
    private lateinit var listenSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var soundText: TextView
    private lateinit var progress: ProgressBar
    private var testPlayer: FartPlayer? = null
    private var settingSwitch = false
    private var downloading = false
    private var awaitingPerms = false
    private var cancelDownload = false

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_SOUND = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        triggerInput = findViewById(R.id.triggerInput)
        listenSwitch = findViewById(R.id.listenSwitch)
        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        soundText = findViewById(R.id.soundText)
        progress = findViewById(R.id.progress)

        triggerInput.setText(Prefs.trigger(this))

        findViewById<Button>(R.id.saveButton).setOnClickListener { saveWord(showToast = true) }

        listenSwitch.setOnCheckedChangeListener { _, on ->
            if (settingSwitch) return@setOnCheckedChangeListener
            if (on) turnOn() else turnOff()
        }

        findViewById<Button>(R.id.testButton).setOnClickListener {
            if (testPlayer == null) testPlayer = FartPlayer(this)
            // SoundPool loads asynchronously; first tap right after launch may need a moment
            testPlayer?.play()
        }

        findViewById<Button>(R.id.pickSoundButton).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*")
            startActivityForResult(i, REQ_SOUND)
        }

        findViewById<Button>(R.id.builtInButton).setOnClickListener {
            Prefs.setCustomSound(this, null)
            refreshSoundLabel()
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener { askBatteryExemption() }

        testPlayer = FartPlayer(this)
        refreshSoundLabel()
    }

    override fun onResume() {
        super.onResume()
        ListenState.onChange = { refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        ListenState.onChange = null
    }

    override fun onDestroy() {
        testPlayer?.release()
        testPlayer = null
        super.onDestroy()
    }

    private fun refresh() {
        setSwitch(ListenState.running || (downloading && !cancelDownload) || awaitingPerms)
        if (!downloading) statusText.text = ListenState.status
        heardText.text = if (ListenState.heard.isNotEmpty()) "Last heard: \"${ListenState.heard}\"" else ""
    }

    private fun setSwitch(on: Boolean) {
        settingSwitch = true
        listenSwitch.isChecked = on
        settingSwitch = false
    }

    private fun saveWord(showToast: Boolean): Boolean {
        val w = Words.normalize(triggerInput.text.toString())
        if (w.isEmpty()) {
            Toast.makeText(this, "Type a trigger word first", Toast.LENGTH_SHORT).show()
            return false
        }
        val changed = w != Prefs.trigger(this)
        Prefs.setTrigger(this, w)
        triggerInput.setText(w)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(triggerInput.windowToken, 0)
        if (changed && ListenState.running) {
            startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_RELOAD))
        }
        if (showToast) Toast.makeText(this, "Trigger word: \"$w\"", Toast.LENGTH_SHORT).show()
        return true
    }

    // ---- Turning listening on/off ----

    private fun turnOn() {
        if (!saveWord(showToast = false)) { setSwitch(false); return }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            awaitingPerms = true
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        ensureModelThenStart()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMS) return
        awaitingPerms = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ensureModelThenStart()
        } else {
            setSwitch(false)
            statusText.text = "Microphone permission is needed to listen"
        }
    }

    private fun ensureModelThenStart() {
        if (ModelManager.isReady(this)) { startListening(); return }
        if (downloading) return
        downloading = true
        cancelDownload = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        statusText.text = "One-time download of the speech model (~40 MB)…"
        Thread {
            try {
                ModelManager.download(this) { p -> runOnUiThread { progress.progress = p } }
                runOnUiThread {
                    downloading = false
                    progress.visibility = View.GONE
                    if (!cancelDownload) startListening() else refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    progress.visibility = View.GONE
                    setSwitch(false)
                    statusText.text = "Download failed: ${e.message}. Check your internet and try again."
                }
            }
        }.start()
    }

    private fun startListening() {
        startForegroundService(Intent(this, ListenService::class.java))
        setSwitch(true)
    }

    private fun turnOff() {
        cancelDownload = true
        awaitingPerms = false
        stopService(Intent(this, ListenService::class.java))
        setSwitch(false)
    }

    // ---- Extras ----

    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed to run in the background 👍", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SOUND || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        Prefs.setCustomSound(this, uri.toString())
        refreshSoundLabel()
    }

    private fun refreshSoundLabel() {
        val custom = Prefs.customSound(this)
        soundText.text = if (custom == null) "Random built-in farts (5 kinds)" else "My sound: ${fileName(Uri.parse(custom))}"
    }

    private fun fileName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "custom file"
    } catch (e: Exception) {
        "custom file"
    }
}
