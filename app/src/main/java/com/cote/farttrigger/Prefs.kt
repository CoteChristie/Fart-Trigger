package com.cote.farttrigger

import android.content.Context

object Prefs {
    private const val FILE = "fart_prefs"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun trigger(c: Context): String = sp(c).getString("trigger", "") ?: ""
    fun setTrigger(c: Context, v: String) = sp(c).edit().putString("trigger", v).apply()

    /** content:// URI of the user's own sound, or null for the built-in farts. */
    fun customSound(c: Context): String? = sp(c).getString("custom_sound", null)
    fun setCustomSound(c: Context, v: String?) = sp(c).edit().putString("custom_sound", v).apply()
}

object Words {
    /** Lowercase, strip punctuation, collapse whitespace. */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").trim().replace(Regex("\\s+"), " ")

    /** How many times [phrase] appears in [text] as whole words. */
    fun count(text: String, phrase: String): Int {
        val t = normalize(text).split(" ").filter { it.isNotEmpty() }
        val p = normalize(phrase).split(" ").filter { it.isNotEmpty() }
        if (p.isEmpty() || t.size < p.size) return 0
        var n = 0
        var i = 0
        while (i <= t.size - p.size) {
            if (t.subList(i, i + p.size) == p) { n++; i += p.size } else i++
        }
        return n
    }
}
