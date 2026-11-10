package app.aether

import android.content.Context
import android.os.Build
import java.io.File

/**
 * When AETHER closes unexpectedly, what went wrong is written to a file on the phone, and nowhere
 * else: no crash SDK, nothing sent. Settings says so next time and lets the listener share it, if they
 * want to, or clear it. Android then ends the app as it would have anyway.
 */
object CrashNote {
    private const val FILE = "last-crash.txt"

    fun keep(context: Context) {
        val app = context.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                File(app.filesDir, FILE).writeText(
                    "AETHER $version on Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL}), " +
                        "thread ${thread.name}, ${java.time.Instant.now()}\n\n" + error.stackTraceToString(),
                )
            }
            before?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.isFile }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
