package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Этап 1 — проверка планшета.
 *
 * Слева/сверху — панель: модель распознавания, результат, данные о пере, сведения об устройстве
 * и отчёт. Справа/снизу — поле для свободного письма пером.
 */
class MainActivity : Activity(), InkCanvasView.Listener {

    private class ModelRow(val status: TextView, val download: Button, val delete: Button)

    private lateinit var engine: HandwritingEngine
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var canvasColumn: LinearLayout
    private lateinit var canvasView: InkCanvasView
    private lateinit var langRu: RadioButton
    private lateinit var langEn: RadioButton
    private lateinit var resultText: TextView
    private lateinit var resultInfo: TextView
    private lateinit var candidatesRow: LinearLayout
    private lateinit var docEdit: EditText
    private lateinit var penText: TextView
    private lateinit var delayText: TextView
    private lateinit var deviceText: TextView
    private lateinit var logText: TextView
    private val modelRows = HashMap<String, ModelRow>()

    private var selectedLang = "ru"
    private var autoRecognize = true
    private var delayMs = 700L

    /** Растёт при каждом изменении поля для письма — чтобы не добавить в текст устаревший вариант. */
    private var canvasVersion = 0
    private var candidates: List<Candidate> = emptyList()
    private var candidatesVersion = -1

    private var staticDevice: List<Pair<String, String>> = emptyList()
    private var previousCrash: String? = null
    private val logLines = ArrayList<String>()
    private val attempts = ArrayList<String>()
    private var recognitionCount = 0
    private var fingerToastShown = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val recognizeRunnable = Runnable { recognizeNow(auto = true, commitAfter = false) }
    private var penTextPosted = false
    private val penTextRunnable = Runnable {
        penTextPosted = false
        penText.text = canvasView.stats.summary()
    }
    private val deviceTextRunnable = Runnable { updateDeviceText() }
    private val ticker = object : Runnable {
        override fun run() {
            updateModelRows()
            if (engine.languages.any { it.state == HandwritingEngine.State.DOWNLOADING }) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    // ---------------------------------------------------------------- жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = HandwritingEngine { addLog(it) }
        engine.onChanged = { updateModelRows() }

        buildUi()
        setContentView(root)
        applyOrientation(resources.configuration.orientation)

        addLog("Запуск FreeWrite ${BuildConfig.VERSION_NAME}")
        readPreviousCrash()
        staticDevice = try {
            DeviceInfo.staticLines(this)
        } catch (t: Throwable) {
            listOf("Ошибка" to HandwritingEngine.describe(t))
        }
        updateDeviceText()
        watchNetwork()
        engine.start()
        updateModelRows()
        updateDelayText()
        penText.text = canvasView.stats.summary()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation)
    }

    override fun onResume() {
        super.onResume()
        updateDeviceText()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (_: Throwable) {
            }
        }
        engine.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- поле для письма

    override fun onStrokeStarted() {
        // Пользователь продолжает писать — откладываем распознавание.
        handler.removeCallbacks(recognizeRunnable)
    }

    override fun onStrokeFinished() {
        onCanvasEdited()
    }

    override fun onTouchIgnored(toolType: Int) {
        if (!fingerToastShown && toolType != android.view.MotionEvent.TOOL_TYPE_STYLUS) {
            fingerToastShown = true
            toast("Палец не пишет — пишите пером. Если перо не рисует, включите «Писать и пальцем».")
        }
    }

    override fun onInputChanged() {
        if (!penTextPosted) {
            penTextPosted = true
            handler.postDelayed(penTextRunnable, 120)
        }
    }

    private fun onCanvasEdited() {
        canvasVersion++
        handler.removeCallbacks(recognizeRunnable)
        if (canvasView.strokeCount == 0) {
            engine.invalidatePending()
            showEmptyResult("Напишите слово в поле для письма")
            return
        }
        if (autoRecognize) handler.postDelayed(recognizeRunnable, delayMs)
    }

    private fun clearCanvas() {
        canvasView.clear()
        onCanvasEdited()
    }

    // ---------------------------------------------------------------- распознавание

    private fun recognizeNow(auto: Boolean, commitAfter: Boolean) {
        handler.removeCallbacks(recognizeRunnable)
        val strokes = canvasView.snapshot()
        if (strokes.isEmpty()) {
            if (!auto) toast("Сначала напишите что-нибудь пером")
            return
        }
        val lang = engine.language(selectedLang)
        val version = canvasVersion
        val online = DeviceInfo.isOnline(this)
        resultInfo.setTextColor(MUTED)
        resultInfo.text = "Распознаю…"
        engine.recognize(
            lang, strokes, preContext(),
            canvasView.width.toFloat(), canvasView.height.toFloat(),
        ) { out ->
            // Показываем только ответ на самый свежий запрос. Старые ответы отбрасываем.
            if (out.requestId == engine.latestRequestId) {
                showOutcome(out, lang, online, version)
                if (commitAfter && out.error == null) commitBest()
            }
        }
    }

    private fun showOutcome(out: RecognitionOutcome, lang: HandwritingEngine.Language, online: Boolean, version: Int) {
        recognitionCount++
        val n = recognitionCount
        val stamp = timeNow()
        candidatesRow.removeAllViews()
        if (out.error != null) {
            candidates = emptyList()
            candidatesVersion = -1
            resultText.text = "—"
            resultInfo.setTextColor(ERROR)
            resultInfo.text = out.error
            addAttempt("$stamp #$n ${lang.tag}: ОШИБКА — ${out.error}")
            return
        }
        candidates = out.candidates
        candidatesVersion = version
        val best = out.candidates.firstOrNull()?.text.orEmpty()
        resultText.text = best.ifEmpty { "(нет вариантов)" }
        resultInfo.setTextColor(MUTED)
        resultInfo.text = "${lang.title} · ${out.millis} мс · штрихов ${out.strokeCount} · " +
            (if (online) "интернет есть" else "без интернета") +
            "\nНажмите на вариант, чтобы добавить его в текст"
        out.candidates.take(8).forEachIndexed { i, c ->
            candidatesRow.addView(chip(c.text, primary = i == 0) { commitText(c.text) })
        }
        val list = out.candidates.take(5).joinToString(" | ") { c ->
            c.text + (c.score?.let { s -> " (" + String.format(Locale.US, "%.2f", s) + ")" } ?: "")
        }
        addAttempt(
            "$stamp #$n ${lang.tag} ${out.millis} мс, штрихов ${out.strokeCount}, точек ${out.pointCount}, " +
                "интернет: ${if (online) "да" else "нет"} → $list",
        )
    }

    private fun showEmptyResult(info: String) {
        candidates = emptyList()
        candidatesVersion = -1
        candidatesRow.removeAllViews()
        resultText.text = "—"
        resultInfo.setTextColor(MUTED)
        resultInfo.text = info
    }

    /** «✓ В текст»: добавить лучший вариант; если он устарел — сначала распознать заново. */
    private fun commitBest() {
        if (canvasView.strokeCount == 0) {
            toast("Пока нечего добавлять")
            return
        }
        val best = candidates.firstOrNull()?.text
        if (best != null && candidatesVersion == canvasVersion) {
            commitText(best)
        } else {
            recognizeNow(auto = false, commitAfter = true)
        }
    }

    /** Добавить слово в текст у курсора, очистить поле для письма. */
    private fun commitText(text: String) {
        if (text.isEmpty()) return
        val e = docEdit.text
        var start = docEdit.selectionStart
        var end = docEdit.selectionEnd
        if (start < 0 || end < 0) {
            start = e.length
            end = e.length
        }
        val from = min(start, end)
        val to = max(start, end)
        val before = e.subSequence(0, from).toString()
        val needSpace = before.isNotEmpty() && !before.last().isWhitespace() && text.first() !in ".,!?;:)»…"
        val insert = (if (needSpace) " " else "") + text
        e.replace(from, to, insert)
        docEdit.setSelection(from + insert.length)
        canvasView.clear()
        canvasVersion++
        handler.removeCallbacks(recognizeRunnable)
        engine.invalidatePending()
        showEmptyResult("Добавлено в текст: «$text». Пишите следующее слово.")
        addLog("В текст: «$text»")
    }

    private fun deleteLastWord() {
        val e = docEdit.text
        val end = docEdit.selectionEnd.let { if (it < 0) e.length else it }
        var i = end
        while (i > 0 && e[i - 1].isWhitespace()) i--
        while (i > 0 && !e[i - 1].isWhitespace()) i--
        e.delete(i, end)
    }

    /** До 20 символов текста перед курсором — подсказка распознавателю. */
    private fun preContext(): String {
        val e = docEdit.text
        val pos = docEdit.selectionStart.let { if (it < 0 || it > e.length) e.length else it }
        return e.subSequence(max(0, pos - 20), pos).toString()
    }

    private fun changeDelay(deltaMs: Long) {
        delayMs = (delayMs + deltaMs).coerceIn(200L, 3000L)
        updateDelayText()
        addLog("Пауза перед распознаванием: ${delayMs} мс")
    }

    private fun updateDelayText() {
        delayText.text = String.format(Locale.US, "Пауза перед распознаванием: %.1f с", delayMs / 1000.0)
    }

    // ---------------------------------------------------------------- модели и устройство

    private fun updateModelRows() {
        for (lang in engine.languages) {
            val row = modelRows[lang.tag] ?: continue
            row.status.text = "${lang.title}: ${engine.stateText(lang)}"
            row.status.setTextColor(if (lang.state == HandwritingEngine.State.ERROR) ERROR else INK)
            row.download.isEnabled = lang.model != null &&
                (lang.state == HandwritingEngine.State.MISSING || lang.state == HandwritingEngine.State.ERROR)
            row.delete.isEnabled = lang.state == HandwritingEngine.State.READY
        }
    }

    private fun confirmDelete(lang: HandwritingEngine.Language) {
        AlertDialog.Builder(this)
            .setMessage("Удалить модель «${lang.title}» с планшета? Чтобы снова распознавать, её придётся скачать заново (нужен интернет).")
            .setPositiveButton("Удалить") { _, _ -> engine.delete(lang) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateDeviceText() {
        if (!::deviceText.isInitialized) return
        val lines = staticDevice.map { "${it.first}: ${it.second}" } + ("Сеть: " + DeviceInfo.networkLine(this))
        deviceText.text = lines.joinToString("\n")
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post()
            override fun onLost(network: Network) = post()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = post()
            private fun post() {
                handler.removeCallbacks(deviceTextRunnable)
                handler.postDelayed(deviceTextRunnable, 300)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (t: Throwable) {
            addLog("Не удалось следить за сетью: ${HandwritingEngine.describe(t)}")
        }
    }

    private fun readPreviousCrash() {
        try {
            val f = File(filesDir, FreeWriteApp.CRASH_FILE)
            if (f.exists()) {
                previousCrash = f.readText().take(6000)
                f.delete()
                addLog("Прошлый запуск завершился ошибкой — подробности в отчёте")
            }
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- отчёт

    private fun addLog(msg: String) {
        Log.i(TAG, msg)
        logLines.add("${timeNow()} $msg")
        if (logLines.size > 300) logLines.removeAt(0)
        if (::logText.isInitialized) logText.text = logLines.takeLast(6).joinToString("\n")
    }

    private fun addAttempt(line: String) {
        attempts.add(line)
        if (attempts.size > 200) attempts.removeAt(0)
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        sb.append("FreeWrite ").append(BuildConfig.VERSION_NAME).append(" — отчёт проверки (этап 1)\n")
        sb.append("Время: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("\n\n")
        sb.append("== Устройство ==\n")
        for ((k, v) in staticDevice) sb.append(k).append(": ").append(v).append('\n')
        sb.append("Сеть сейчас: ").append(DeviceInfo.networkLine(this)).append("\n\n")
        sb.append("== ML Kit (распознавание) ==\n")
        engine.initError?.let { sb.append("Ошибка запуска ML Kit: ").append(it).append('\n') }
        for (line in engine.reportLines()) sb.append(line).append('\n')
        sb.append("Пауза перед распознаванием: ").append(delayMs).append(" мс, автораспознавание: ")
            .append(if (autoRecognize) "вкл" else "выкл").append("\n\n")
        sb.append("== Перо ==\n")
        sb.append(canvasView.stats.report()).append("\n\n")
        sb.append("== Распознавания (").append(attempts.size).append(") ==\n")
        for (a in attempts.takeLast(40)) sb.append(a).append('\n')
        sb.append('\n')
        previousCrash?.let { sb.append("== Ошибка прошлого запуска ==\n").append(it).append("\n\n") }
        sb.append("== Журнал ==\n")
        for (l in logLines.takeLast(80)) sb.append(l).append('\n')
        return sb.toString()
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun shareReport() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "FreeWrite — отчёт проверки")
            .putExtra(Intent.EXTRA_TEXT, buildReport())
        try {
            startActivity(Intent.createChooser(intent, "Отправить отчёт"))
        } catch (t: Throwable) {
            copyToClipboard("Отчёт FreeWrite", buildReport())
            toast("Не удалось открыть «Поделиться». Отчёт скопирован — вставьте его в сообщение.")
        }
    }

    // ---------------------------------------------------------------- интерфейс

    private fun buildUi() {
        root = LinearLayout(this).apply { setBackgroundColor(BACKGROUND) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
            // Чтобы при запуске фокус не уходил в поле текста и не вылезала клавиатура.
            isFocusableInTouchMode = true
        }
        panelScroll = ScrollView(this).apply {
            addView(panel, ViewGroup.LayoutParams(MATCH, WRAP))
        }

        panel.addView(label("FreeWrite", 22f, bold = true, color = ACCENT))
        panel.addView(label("Этап 1 · проверка планшета · версия ${BuildConfig.VERSION_NAME}", 12f, color = MUTED))

        // 1. Модель распознавания
        val recCard = card(panel, "1. Модель распознавания")
        val radio = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        langRu = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Русский"
        }
        langEn = RadioButton(this).apply {
            id = View.generateViewId()
            text = "English"
        }
        radio.addView(langRu)
        radio.addView(langEn)
        radio.check(langRu.id)
        radio.setOnCheckedChangeListener { _, checkedId ->
            selectedLang = if (checkedId == langEn.id) "en-US" else "ru"
            addLog("Язык распознавания: $selectedLang")
            if (canvasView.strokeCount > 0) recognizeNow(auto = true, commitAfter = false)
        }
        recCard.addView(row(label("Распознавать:", 14f), radio))
        for (lang in engine.languages) {
            val status = label("${lang.title}: …", 14f)
            val download = smallButton("Скачать") {
                engine.download(lang)
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 1000)
            }
            val delete = smallButton("Удалить") { confirmDelete(lang) }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f))
            line.addView(download)
            line.addView(delete)
            recCard.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
            modelRows[lang.tag] = ModelRow(status, download, delete)
        }
        recCard.addView(
            label(
                "Модель весит около 20 МБ и скачивается один раз. Потом распознавание работает " +
                    "без интернета — проверьте в режиме полёта.",
                12f, color = MUTED,
            ),
        )

        // 2. Результат
        val resCard = card(panel, "2. Результат")
        resultText = label("—", 28f, color = INK).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        resultInfo = label("Напишите слово в поле для письма", 12f, color = MUTED)
        candidatesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }
        val candScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidatesRow)
        }
        resCard.addView(resultText)
        resCard.addView(resultInfo)
        resCard.addView(candScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        resCard.addView(label("Текст:", 12f, color = MUTED).apply { setPadding(0, dp(6), 0, dp(2)) })
        docEdit = EditText(this).apply {
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = 17f
            setTextColor(INK)
            hint = "Здесь собирается распознанный текст. Курсор можно поставить в любое место."
            background = rounded(PAPER, LINE, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        resCard.addView(docEdit, LinearLayout.LayoutParams(MATCH, WRAP))
        resCard.addView(
            buttonRow(
                smallButton("Копировать текст") {
                    copyToClipboard("Текст FreeWrite", docEdit.text.toString())
                    toast("Текст скопирован")
                },
                smallButton("Удалить слово") { deleteLastWord() },
                smallButton("Очистить текст") { docEdit.setText("") },
            ),
        )

        // 3. Перо
        val penCard = card(panel, "3. Перо")
        penText = label("", 13f)
        penCard.addView(penText)
        val fingerCheck = CheckBox(this).apply {
            text = "Писать и пальцем (если перо не рисует)"
            setOnCheckedChangeListener { _, on ->
                canvasView.allowFinger = on
                canvasView.hint = if (on) "Пишите пером или пальцем — в любом месте" else "Пишите пером здесь — в любом месте"
                addLog(if (on) "Палец тоже пишет" else "Пишет только перо")
            }
        }
        val autoCheck = CheckBox(this).apply {
            text = "Распознавать само после паузы"
            isChecked = true
            setOnCheckedChangeListener { _, on ->
                autoRecognize = on
                addLog(if (on) "Автораспознавание включено" else "Автораспознавание выключено")
            }
        }
        delayText = label("", 14f)
        penCard.addView(fingerCheck)
        penCard.addView(autoCheck)
        penCard.addView(
            row(
                delayText,
                smallButton("−") { changeDelay(-100) },
                smallButton("+") { changeDelay(100) },
            ),
        )

        // 4. Устройство
        val devCard = card(panel, "4. Устройство")
        deviceText = label("", 12f).apply { setTextIsSelectable(true) }
        devCard.addView(deviceText)

        // 5. Отчёт
        val repCard = card(panel, "5. Отчёт для разработчика")
        repCard.addView(
            label(
                "Когда пройдёте проверку по инструкции, нажмите «Отправить отчёт» " +
                    "(или «Скопировать отчёт») и перешлите текст нам.",
                12f, color = MUTED,
            ),
        )
        repCard.addView(
            buttonRow(
                smallButton("Отправить отчёт") { shareReport() },
                smallButton("Скопировать отчёт") {
                    copyToClipboard("Отчёт FreeWrite", buildReport())
                    toast("Отчёт скопирован")
                },
            ),
        )
        logText = label("", 11f, color = MUTED).apply { typeface = Typeface.MONOSPACE }
        repCard.addView(logText)

        // Поле для письма
        canvasColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(label("Поле для письма  ", 14f, bold = true, color = ACCENT))
        tools.addView(smallButton("↶ Штрих") { if (canvasView.undoLast()) onCanvasEdited() })
        tools.addView(smallButton("Очистить") { clearCanvas() })
        tools.addView(smallButton("Распознать") { recognizeNow(auto = false, commitAfter = false) })
        tools.addView(smallButton("✓ В текст") { commitBest() })
        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }
        canvasColumn.addView(toolsScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        canvasView = InkCanvasView(this).apply {
            background = rounded(PAPER, LINE, 14)
            listener = this@MainActivity
        }
        canvasColumn.addView(canvasView, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(4) })

        root.addView(panelScroll)
        root.addView(canvasColumn)
    }

    /** Вертикально — панель сверху, поле снизу; горизонтально — панель слева, поле справа. */
    private fun applyOrientation(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        panelScroll.layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, MATCH, 0.4f) else LinearLayout.LayoutParams(MATCH, 0, 0.46f)
        canvasColumn.layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, MATCH, 0.6f) else LinearLayout.LayoutParams(MATCH, 0, 0.54f)
        root.requestLayout()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun label(value: String, sp: Float, bold: Boolean = false, color: Int = INK): TextView =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        c.addView(label(title, 15f, bold = true, color = ACCENT).apply { setPadding(0, 0, 0, dp(4)) })
        parent.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        return c
    }

    private fun smallButton(title: String, onClick: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
    }

    private fun chip(title: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 17f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(if (primary) Color.WHITE else ACCENT)
        background = rounded(if (primary) ACCENT else PAPER, ACCENT, 20)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v)
    }

    private fun buttonRow(vararg views: View): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row(*views))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun timeNow(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val TAG = "FreeWrite"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val INK = Color.rgb(26, 32, 56)
        val MUTED = Color.rgb(96, 104, 122)
        val ACCENT = Color.rgb(47, 75, 124)
        val ERROR = Color.rgb(176, 48, 48)
        val PAPER = Color.rgb(255, 253, 248)
        val CARD = Color.WHITE
        val LINE = Color.rgb(221, 215, 202)
        val BACKGROUND = Color.rgb(244, 241, 234)
    }
}
