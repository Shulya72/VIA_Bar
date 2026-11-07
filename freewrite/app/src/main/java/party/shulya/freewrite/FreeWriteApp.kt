package party.shulya.freewrite

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Если приложение упадёт (например, ML Kit не запустится на HarmonyOS), текст ошибки
 * сохраняется в файл. При следующем запуске он попадёт в отчёт — так мы узнаем причину,
 * а не будем гадать.
 */
class FreeWriteApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(base.filesDir, CRASH_FILE).writeText("${Date()} (поток ${thread.name})\n$sw")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
