package party.shulya.freewrite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Обновление приложения изнутри: смотрит тестовый выпуск на GitHub, скачивает новый APK
 * и открывает системный установщик. Android всегда спрашивает подтверждение «Установить» —
 * обойти это нельзя, но браузер и поиск файла больше не нужны.
 */
object Updater {
    /** Тестовый выпуск, куда GitHub Actions кладёт каждую сборку. */
    const val RELEASE_API = "https://api.github.com/repos/Shulya72/bar-menu/releases/tags/freewrite-latest"

    class Release(val version: String, val apkUrl: String, val size: Long)

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun check(done: (Release?, String?) -> Unit) {
        exec.execute {
            try {
                val c = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "FreeWrite-Android")
                }
                val code = c.responseCode
                if (code != 200) {
                    main.post { done(null, "сервер обновлений ответил $code") }
                    return@execute
                }
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val assets = json.getJSONArray("assets")
                var found: Release? = null
                val re = Regex("""FreeWrite-(\d+(?:\.\d+)*)\.apk""")
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val m = re.matchEntire(a.optString("name")) ?: continue
                    found = Release(m.groupValues[1], a.getString("browser_download_url"), a.optLong("size"))
                }
                val result = found
                main.post { done(result, if (result == null) "в выпуске нет файла приложения" else null) }
            } catch (t: Throwable) {
                main.post { done(null, "нет связи с сервером обновлений (${t.javaClass.simpleName})") }
            }
        }
    }

    /** true, если версия remote новее local (сравнение по числам: 0.10.0 > 0.9.3). */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val b = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun download(context: Context, r: Release, progress: (Int) -> Unit, done: (File?, String?) -> Unit) {
        val dir = File(context.cacheDir, "updates")
        exec.execute {
            try {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "FreeWrite-${r.version}.apk")
                var url = URL(r.apkUrl)
                var hops = 0
                var conn: HttpURLConnection? = null
                while (conn == null) {
                    val attempt = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "FreeWrite-Android")
                    }
                    val code = attempt.responseCode
                    if (code in 300..399 && hops < 6) {
                        url = URL(url, attempt.getHeaderField("Location"))
                        hops++
                        attempt.disconnect()
                    } else if (code == 200) {
                        conn = attempt
                    } else {
                        throw IllegalStateException("сервер ответил $code")
                    }
                }
                val c: HttpURLConnection = conn ?: error("нет соединения")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                var read = 0L
                var lastPct = -1
                c.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { progress(pct) }
                                }
                            }
                        }
                    }
                }
                main.post { done(target, null) }
            } catch (t: Throwable) {
                main.post { done(null, t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    /**
     * Открыть системный установщик. Если FreeWrite ещё не разрешено ставить приложения —
     * открыть настройку разрешения и вернуть false.
     */
    fun install(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return true
    }
}
