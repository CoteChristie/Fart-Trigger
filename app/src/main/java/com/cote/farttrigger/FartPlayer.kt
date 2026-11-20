package com.cote.farttrigger

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri

/** Plays a random built-in fart, or the user's chosen sound file. */
class FartPlayer(private val context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val loaded = mutableSetOf<Int>()
    private val ids: List<Int>
    private var lastIndex = -1

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        ids = listOf(
            R.raw.fart_classic, R.raw.fart_quick, R.raw.fart_long, R.raw.fart_squeaker,
            R.raw.fart_sputter, R.raw.fart_wet, R.raw.fart_rumbler, R.raw.fart_question
        ).map { pool.load(context, it, 1) }
    }

    fun play() {
        val custom = Prefs.customSound(context)
        if (custom != null && playCustom(Uri.parse(custom))) return
        val ready = ids.filter { it in loaded }
        if (ready.isEmpty()) return
        // Pick a random one, but not the same one twice in a row
        var i = ready.indices.random()
        if (ready.size > 1 && i == lastIndex) i = (i + 1) % ready.size
        lastIndex = i
        val pitch = 0.92f + Math.random().toFloat() * 0.16f   // a little variety
        pool.play(ready[i], 1f, 1f, 1, 0, pitch)
    }

    private fun playCustom(uri: Uri): Boolean = try {
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        )
        mp.setDataSource(context, uri)
        mp.setOnCompletionListener { it.release() }
        mp.setOnErrorListener { p, _, _ -> p.release(); true }
        mp.prepare()
        mp.start()
        true
    } catch (e: Exception) {
        false
    }

    fun release() = pool.release()
}
