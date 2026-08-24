package de.lobianco.saftssh.rustdesk.data.logging

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-app debug log file the user can share for support purposes — ported from the main LobiShell
 * app's identical class (`de.lobianco.saftssh.data.logging.LogFileManager`); see that file for the
 * full design rationale (direct-write instead of `exec("logcat")`, since some OEMs block that for
 * third-party apps).
 *
 * This covers Android/Kotlin-side events only (session binder lifecycle, connect/disconnect,
 * errors) — the vendored RustDesk Rust core has its own, much more detailed logging that this
 * deliberately does NOT duplicate: `NativeBridge.initialize()` sets it up to write to
 * `.log` files into `filesDir/RustDesk/Logs` (release builds; debug builds go to logcat under tag "ffi"
 * instead). [InfoActivity]'s "Submit Logs" bundles both sources together — see its doc.
 *
 * This plugin has no dependency-injection framework, so unlike the main app's `@Singleton` Hilt
 * class this is a plain `object` — [init] must be called once, from
 * [de.lobianco.saftssh.rustdesk.RustDeskPluginApplication.onCreate], before [log] does anything
 * useful.
 */
object LogFileManager {
    private const val TAG = "LogFileManager"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "rustdesk_plugin_debug.log"
    private const val MAX_BYTES = 2_000_000L
    private const val KEEP_BYTES = 1_000_000L

    private lateinit var appContext: Context
    lateinit var logFile: File
        private set
    private val writeCount = AtomicInteger(0)
    private var initialized = false

    /** Call once, as early as possible (Application.onCreate()). Writes the session header. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        logFile = File(File(appContext.filesDir, LOG_DIR).also { it.mkdirs() }, LOG_FILE)
        initialized = true
        rotateIfNeeded()
        writeSessionHeader()
        Log.d(TAG, "Log capture started -> ${logFile.absolutePath}")
    }

    /** Write one log line directly to the file. Called by [AppLog] on every log statement. */
    @Synchronized
    fun log(level: String, tag: String, message: String) {
        if (!initialized) return
        runCatching {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            logFile.appendText("$ts $level/$tag: $message\n")
            if (writeCount.incrementAndGet() % 200 == 0 && logFile.length() > MAX_BYTES) rotateNow()
        }
    }

    /** FileProvider URI suitable for ACTION_SEND / share sheet. Reuses the plugin's existing
     *  `de.lobianco.saftssh.rustdesk.fileprovider` provider, whose `file_paths.xml` already exposes
     *  the whole filesDir root (`path="."`, originally for file-transfer downloads) — this file
     *  falls under that same root, so no new `<files-path>` entry was needed. */
    fun getShareUri() = FileProvider.getUriForFile(
        appContext, "${appContext.packageName}.fileprovider", logFile
    )!!

    /** Human-readable file size for the UI. */
    fun fileSizeKb(): Long = if (::logFile.isInitialized && logFile.exists()) logFile.length() / 1024 else 0L

    /** Wipes the log file (user-triggered). */
    fun clearLog() {
        runCatching { logFile.delete() }
        writeSessionHeader()
    }

    // ── Session header ────────────────────────────────────────────────────────

    private fun writeSessionHeader() {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val line = "═".repeat(60)
        val versionName = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val text = buildString {
            appendLine()
            appendLine(line)
            appendLine("LobiShell RustDesk Plugin Debug Log — Session Start")
            appendLine("Time:           ${sdf.format(Date())}")
            appendLine("Plugin version: $versionName")
            appendLine("Device:         ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android:        ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI:            ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine(line)
        }
        runCatching { logFile.appendText(text) }
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    private fun rotateIfNeeded() {
        if (logFile.exists() && logFile.length() > MAX_BYTES) rotateNow()
    }

    private fun rotateNow() {
        runCatching {
            val content = logFile.readText()
            if (content.length > KEEP_BYTES) {
                logFile.writeText("…[older entries trimmed]\n" + content.substring(content.length - KEEP_BYTES.toInt()))
            }
        }
    }
}
