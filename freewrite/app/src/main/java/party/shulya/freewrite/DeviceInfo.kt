package party.shulya.freewrite

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Сведения об устройстве для отчёта: версия HarmonyOS и Android API, экран, память,
 * наличие сервисов Google и Huawei, сеть, устройства ввода с пером.
 */
object DeviceInfo {

    fun staticLines(activity: Activity): List<Pair<String, String>> {
        val lines = ArrayList<Pair<String, String>>()
        // Каждая строка отдельно: если одну узнать не удалось, остальные всё равно видны.
        fun line(name: String, value: () -> String) {
            lines += name to try {
                value()
            } catch (t: Throwable) {
                "не удалось узнать (${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)})"
            }
        }
        line("Устройство") { "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})" }
        line("Система") { osLine() }
        line("Android API") { "${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})" }
        line("Сборка") { Build.DISPLAY }
        line("Процессор") { cpuLine() }
        line("Память") { memoryLine(activity) }
        line("Экран") { screenLine(activity) }
        line("Сервисы Google") { packageVersion(activity, "com.google.android.gms") ?: "не установлены" }
        line("Google Play") { packageVersion(activity, "com.android.vending") ?: "не установлен" }
        line("HMS Core (Huawei)") { packageVersion(activity, "com.huawei.hwid") ?: "не установлен" }
        line("Перо в системе") { stylusLine() }
        return lines
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun networkLine(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "неизвестно"
        val network = cm.activeNetwork ?: return "нет (офлайн)"
        val caps = cm.getNetworkCapabilities(network) ?: return "нет (офлайн)"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "кабель"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "другая сеть"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (validated) "есть ($kind)" else "$kind без доступа в интернет"
    }

    private fun osLine(): String {
        val harmony = sysProp("hw_sc.build.platform.version")
        val brand = osBrand()
        val emui = sysProp("ro.build.version.emui")
        val parts = ArrayList<String>()
        if (harmony.isNotEmpty()) {
            parts += "HarmonyOS $harmony"
        } else if (brand.equals("harmony", ignoreCase = true)) {
            parts += "HarmonyOS"
        }
        if (emui.isNotEmpty()) parts += emui
        if (parts.isEmpty()) parts += "Android"
        return parts.joinToString(" · ")
    }

    private fun cpuLine(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else ""
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        return listOf(soc, Build.HARDWARE, abis)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" · ")
    }

    private fun memoryLine(ctx: Context): String {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return "?"
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return String.format(
            Locale.US, "%.1f ГБ всего, свободно %.1f ГБ",
            mi.totalMem / 1073741824.0, mi.availMem / 1073741824.0,
        )
    }

    @Suppress("DEPRECATION")
    private fun screenLine(activity: Activity): String {
        val m = activity.resources.displayMetrics
        val display: Display? = try {
            if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
        } catch (_: Throwable) {
            activity.windowManager.defaultDisplay
        }
        val mode = display?.mode
        val size = if (mode != null) "${mode.physicalWidth}×${mode.physicalHeight}" else "${m.widthPixels}×${m.heightPixels}"
        val rate = display?.refreshRate?.let { String.format(Locale.US, "%.0f Гц", it) } ?: "?"
        val rates = display?.supportedModes
            ?.map { it.refreshRate.roundToInt() }
            ?.distinct()
            ?.sorted()
            ?.joinToString("/")
            .orEmpty()
        val extra = if (rates.isNotEmpty()) " (режимы: $rates Гц)" else ""
        return "$size px · ${m.densityDpi} dpi · $rate$extra"
    }

    @Suppress("DEPRECATION")
    private fun packageVersion(ctx: Context, pkg: String): String? = try {
        val info = ctx.packageManager.getPackageInfo(pkg, 0)
        info.versionName ?: "установлено"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (t: Throwable) {
        "не удалось проверить (${t.javaClass.simpleName})"
    }

    private fun stylusLine(): String {
        val found = ArrayList<String>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (!d.supportsSource(InputDevice.SOURCE_STYLUS)) continue
            val axes = ArrayList<String>()
            if (d.getMotionRange(MotionEvent.AXIS_PRESSURE) != null) axes += "нажим"
            if (d.getMotionRange(MotionEvent.AXIS_TILT) != null) axes += "наклон"
            if (d.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null) axes += "поворот"
            if (d.getMotionRange(MotionEvent.AXIS_DISTANCE) != null) axes += "высота над экраном"
            found += "${d.name} [${if (axes.isEmpty()) "оси не указаны" else axes.joinToString(", ")}]"
        }
        return if (found.isEmpty()) {
            "система не сообщает об устройстве-стилусе (проверим по касаниям)"
        } else {
            found.joinToString("; ")
        }
    }

    /** Системное свойство Android (getprop). Пустая строка, если недоступно. */
    private fun sysProp(key: String): String {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            val v = m.invoke(null, key) as? String
            if (!v.isNullOrBlank()) return v.trim()
        } catch (_: Throwable) {
        }
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val v = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor(2, TimeUnit.SECONDS)
            if (v.isNotEmpty()) return v
        } catch (_: Throwable) {
        }
        return ""
    }

    /** На HarmonyOS 2–4 возвращает "harmony". */
    private fun osBrand(): String = try {
        val c = Class.forName("com.huawei.system.BuildEx")
        (c.getMethod("getOsBrand").invoke(null) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }
}
