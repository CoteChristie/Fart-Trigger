package com.cote.farttrigger

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads the small offline English speech model (~40 MB) once.
 * After that, recognition works with no internet at all.
 */
object ModelManager {
    private const val URL_STR =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    private fun root(c: Context) = File(c.filesDir, "vosk-model")
    private fun marker(c: Context) = File(c.filesDir, "vosk-model.ready")

    fun isReady(c: Context) = marker(c).exists() && modelPath(c) != null

    /** Folder that actually contains the model files (the zip has a sub-folder). */
    fun modelPath(c: Context): String? {
        val r = root(c)
        if (File(r, "am").isDirectory || File(r, "conf").isDirectory) return r.absolutePath
        return r.listFiles()?.firstOrNull {
            it.isDirectory && (File(it, "am").isDirectory || File(it, "conf").isDirectory)
        }?.absolutePath
    }

    /** Blocking: call from a background thread. [progress] gets 0..100. */
    fun download(c: Context, progress: (Int) -> Unit) {
        val zip = File(c.cacheDir, "model.zip")
        val conn = URL(URL_STR).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) throw Exception("Download failed (HTTP ${conn.responseCode})")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(zip).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 90 / total).toInt()
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
        }
        val dest = root(c)
        dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(destPath)) continue // ignore bad paths
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zis.copyTo(it) }
                }
            }
        }
        zip.delete()
        if (modelPath(c) == null) throw Exception("Model files missing after unzip")
        marker(c).writeText("ok")
        progress(100)
    }
}
