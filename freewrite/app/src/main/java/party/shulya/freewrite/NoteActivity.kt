package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Экран «Запись» (этап 2).
 *
 * Пишете пером в любом месте листа. После паузы (или когда начинаете писать в другом месте)
 * надпись распознаётся: слово серым появляется у курсора, затем становится текстом,
 * а рукопись исчезает. Ручка и маркер оставляют рисунок рисунком. Пальцем — прокрутка,
 * галочки задач и перенос курсора.
 */
class NoteActivity : Activity(), NoteInkView.Host {

    enum class Tool { WRITE, PEN, MARKER, ERASER, HAND }

    private lateinit var store: NoteStore
    private lateinit var note: Note
    private lateinit var engine: HandwritingEngine
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var scroll: ScrollView
    private lateinit var sheet: RuledSheet
    private lateinit var ink: NoteInkView
    private lateinit var mini: LinearLayout
    private lateinit var hintView: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private lateinit var titleView: TextView
    private val toolButtons = HashMap<Tool, Ui.ToolButton>()
    private val colorViews = ArrayList<Pair<Int, View>>()

    private var tool = Tool.WRITE
    private var penColor = Ui.INK
    private var fingerWrites = false

    private val blockViews = ArrayList<View>()
    private var caretTextView: TextView? = null
    private var interim = ""
    private var caretOn = true
    private val bitmapCache = HashMap<String, Bitmap>()

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    private var lineHeightPx = 0
    private var pauseMs = 1000L

    // ---------------------------------------------------------------- письмо в текст

    private inner class Group(val id: Int) {
        val strokes = ArrayList<InkStroke>()
        var candidates: List<Candidate>? = null
        var resultFor = -1
        var committing = false
        var error: String? = null
    }

    private var current: Group? = null
    private val commitQueue = ArrayDeque<Group>()
    private val afterCommit = ArrayDeque<() -> Unit>()
    private var groupSeq = 0
    private val wordHeights = ArrayList<Float>()
    private val interimRunnable = Runnable { current?.let { recognize(it) } }
    private val commitRunnable = Runnable { current?.let { commit(it) } }
    private val caretBlink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            refreshCaretText()
            handler.postDelayed(this, 530)
        }
    }
    private val saveRunnable = Runnable { saveNow() }
    private val bannerTicker = object : Runnable {
        override fun run() {
            updateBanner()
            if (engine.language("ru").state == HandwritingEngine.State.DOWNLOADING) handler.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        val id = intent.getStringExtra(EXTRA_ID)
        note = id?.let { store.load(it) } ?: Note.create("Совещание").also { store.save(it) }
        lineHeightPx = Ui.dp(this, 36f)
        engine = HandwritingEngine { android.util.Log.i("FreeWrite", it) }
        engine.onChanged = { updateBanner() }
        buildUi()
        setContentView(rootView)
        engine.start(this)
        renderBlocks()
        updateBanner()
        setTool(Tool.WRITE)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(caretBlink)
        handler.postDelayed(caretBlink, 530)
    }

    override fun onPause() {
        handler.removeCallbacks(caretBlink)
        current?.let { commit(it) }
        saveNow()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.close()
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        val finishNow = Runnable { saveNow(); finish() }
        handler.postDelayed(finishNow, 2500) // если распознавание зависло — всё равно выходим
        runAfterCommit {
            handler.removeCallbacks(finishNow)
            finishNow.run()
        }
    }

    // ---------------------------------------------------------------- интерфейс

    private lateinit var rootView: LinearLayout

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun buildUi() {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.DESK)
        }

        // Полоса инструментов
        val row = LinearLayout(this)
        val back = Ui.ToolButton(this, R.drawable.ic_back, "Назад").apply { setOnClickListener { onBackPressed() } }
        back.minimumWidth = dp(44f)
        row.addView(back)
        titleView = Ui.text(this, note.title, 16f, Ui.INK, bold = true).apply {
            maxWidth = dp(220f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4f), 0, dp(10f), 0)
            setOnClickListener { renameNote() }
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(Ui.mono(this@NoteActivity, Ui.noteMeta(note.created), 11f).apply { setPadding(dp(4f), 0, dp(10f), 0) })
            setOnClickListener { renameNote() }
        }
        row.addView(titleBox)
        row.addView(Ui.separator(this))
        addTool(row, Tool.WRITE, R.drawable.ic_write, "В текст")
        addTool(row, Tool.PEN, R.drawable.ic_pen, "Ручка")
        addTool(row, Tool.MARKER, R.drawable.ic_marker, "Маркер")
        addTool(row, Tool.ERASER, R.drawable.ic_eraser, "Ластик")
        addTool(row, Tool.HAND, R.drawable.ic_hand, "Указка")
        for (c in listOf(Ui.INK, Ui.PEN_BLUE, Ui.PEN_RED)) {
            val v = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                }
                setOnClickListener {
                    penColor = c
                    if (tool == Tool.WRITE || tool == Tool.ERASER || tool == Tool.HAND) setTool(Tool.PEN)
                    refreshColors()
                }
                contentDescription = "Цвет"
            }
            row.addView(v, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { setMargins(dp(5f), 0, dp(5f), 0) })
            colorViews += c to v
        }
        row.addView(Ui.separator(this))
        row.addView(Ui.ToolButton(this, R.drawable.ic_image, "Картинка").apply { setOnClickListener { pickImage() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_undo, "Отменить").apply { setOnClickListener { undo() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_redo, "Вернуть").apply { setOnClickListener { redo() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_sum, "Итоги").apply { setOnClickListener { showSummary() } })
        row.addView(Ui.separator(this))
        row.addView(CheckBox(this).apply {
            text = "Палец пишет"
            textSize = 13f
            setTextColor(Ui.INK2)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, on -> fingerWrites = on }
        })
        rootView.addView(Ui.toolbar(this, row), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        rootView.addView(View(this).apply { setBackgroundColor(Ui.RULE) }, LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))

        // Плашка «скачайте модель»
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.WARN_SOFT)
            setPadding(dp(16f), dp(8f), dp(12f), dp(8f))
        }
        bannerText = Ui.text(this, "", 14f, Ui.WARN)
        bannerButton = Ui.text(this, "Скачать", 14f, Color.WHITE, bold = true).apply {
            background = Ui.rounded(this@NoteActivity, Ui.ACCENT, null, 10f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                engine.download(engine.language("ru"))
                handler.removeCallbacks(bannerTicker)
                handler.postDelayed(bannerTicker, 1000)
            }
        }
        banner.addView(bannerText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        banner.addView(bannerButton)
        rootView.addView(banner, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // Лист и слой пера
        val area = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }
        val pad = FrameLayout(this).apply { setPadding(dp(12f), dp(14f), dp(12f), dp(14f)) }
        sheet = RuledSheet(this).apply {
            lineGap = lineHeightPx.toFloat()
            background = Ui.rounded(this@NoteActivity, Ui.PAPER, Ui.RULE, 6f)
            setPadding(dp(56f), dp(18f), dp(36f), dp(24f))
        }
        pad.addView(sheet, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.addView(pad, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> ink.invalidate() }
        area.addView(scroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        ink = NoteInkView(this, this)
        area.addView(ink, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(this@NoteActivity, Ui.PAPER, Ui.RULE, 14f)
            elevation = dp(6f).toFloat()
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        fun miniButton(icon: Int, label: String, action: () -> Unit): Ui.ToolButton {
            val b = Ui.ToolButton(this, icon, label).apply {
                minimumWidth = dp(48f)
                setOnClickListener { action() }
            }
            mini.addView(b)
            return b
        }
        miniButton(R.drawable.ic_space, "пробел") { runAfterCommit { insertAtCaret(" ", raw = true) } }
        // «буква»: одно нажатие — одна буква; если держать — стирает подряд, как клавиатура.
        miniButton(R.drawable.ic_backspace, "буква") {}.apply {
            tag = "mini_letter"
            setOnTouchListener(repeatOnHold { runAfterCommit { deleteChar() } })
        }
        miniButton(R.drawable.ic_backspace_word, "слово") { runAfterCommit { deleteWord() } }
        miniButton(R.drawable.ic_enter, "строка") { runAfterCommit { newLine(task = false) } }
        miniButton(R.drawable.ic_task, "задача") { runAfterCommit { newLine(task = true) } }
        miniButton(R.drawable.ic_edit, "править") { runAfterCommit { editCaretDialog() } }
        area.addView(mini, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(10f), dp(14f), 0)
        })

        hintView = Ui.text(this, "", 13f, Ui.PAPER).apply {
            background = Ui.rounded(this@NoteActivity, Ui.INK, null, 999f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            alpha = 0f
            gravity = Gravity.CENTER
        }
        area.addView(hintView, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18f)
        })
        rootView.addView(area, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        refreshColors()
    }

    private fun addTool(row: LinearLayout, t: Tool, icon: Int, label: String) {
        val b = Ui.ToolButton(this, icon, label).apply { setOnClickListener { setTool(t) } }
        b.tag = "tool_" + t.name.lowercase()
        toolButtons[t] = b
        row.addView(b)
    }

    private fun setTool(t: Tool) {
        if (t != Tool.WRITE) current?.let { commit(it) }
        tool = t
        for ((k, v) in toolButtons) v.selectedTool = k == t
        mini.visibility = if (t == Tool.WRITE) View.VISIBLE else View.GONE
        refreshColors()
    }

    private fun refreshColors() {
        for ((c, v) in colorViews) {
            val selected = c == penColor && (tool == Tool.PEN || tool == Tool.MARKER)
            v.scaleX = if (selected) 1.25f else 1f
            v.scaleY = v.scaleX
            v.alpha = if (selected || tool == Tool.PEN || tool == Tool.MARKER) 1f else 0.55f
        }
    }

    private fun hint(text: String, ms: Long = 2600) {
        hintView.text = text
        hintView.animate().cancel()
        hintView.alpha = 1f
        hintView.animate().alpha(0f).setStartDelay(ms).setDuration(300).start()
    }

    private fun updateBanner() {
        if (!::banner.isInitialized) return
        val ru = engine.language("ru")
        when (ru.state) {
            HandwritingEngine.State.READY -> banner.visibility = View.GONE
            HandwritingEngine.State.DOWNLOADING -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "Русская модель: ${engine.stateText(ru)}"
                bannerButton.visibility = View.GONE
            }
            HandwritingEngine.State.MISSING, HandwritingEngine.State.ERROR -> {
                banner.visibility = View.VISIBLE
                bannerText.text = if (ru.state == HandwritingEngine.State.ERROR) {
                    "Модель распознавания: ${engine.stateText(ru)}"
                } else {
                    "Для письма в текст нужна русская модель распознавания (около 20 МБ, один раз)."
                }
                bannerButton.visibility = if (ru.model != null) View.VISIBLE else View.GONE
            }
            else -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "Проверяю модель распознавания…"
                bannerButton.visibility = View.GONE
            }
        }
    }

    // ---------------------------------------------------------------- блоки на листе

    private fun textStyle(tv: TextView) {
        tv.textSize = 19f
        tv.setTextColor(Ui.INK)
        tv.setExactLineHeight(lineHeightPx)
        tv.tag = RuledSheet.RULED
        tv.minHeight = lineHeightPx
    }

    private fun renderBlocks() {
        sheet.removeAllViews()
        blockViews.clear()
        caretTextView = null
        val title = Ui.text(this, note.title, 26f, Ui.INK, bold = true).apply { setPadding(0, 0, 0, dp(2f)) }
        sheet.addView(title)
        sheet.addView(Ui.mono(this, Ui.noteMeta(note.created), 12f).apply { setPadding(0, 0, 0, dp(10f)) })
        note.blocks.forEachIndexed { i, b ->
            val v: View = when (b) {
                is Block.Para -> TextView(this).apply {
                    textStyle(this)
                    text = b.text
                    caretOnTap(this, i)
                    if (i == note.caret) caretTextView = this
                }
                is Block.Task -> taskView(b, i)
                is Block.Image -> imageView(b, i)
            }
            blockViews += v
            sheet.addView(v, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        // Пустое место ниже текста: здесь тоже можно писать; нажатие — курсор в конец.
        val spacer = View(this).apply {
            minimumHeight = max(dp(420f), (resources.displayMetrics.heightPixels * 0.55f).toInt())
            setOnClickListener { setCaret(lastTextBlock()) }
        }
        sheet.addView(spacer, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        refreshCaretText()
        ink.invalidate()
    }

    private fun taskView(b: Block.Task, i: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(if (b.done) Color.WHITE else Color.TRANSPARENT)
            background = Ui.rounded(this@NoteActivity, if (b.done) Ui.OK else Ui.PAPER, if (b.done) Ui.OK else Ui.INK2, 6f)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            contentDescription = if (b.done) "Сдано" else "Не сдано"
            setOnClickListener { toggleTask(i) }
        }
        val box = FrameLayout(this).apply {
            // Текст стоит в верхней части строки (запас высоты — снизу), поэтому галочку
            // выравниваем по верху, а не по центру строки.
            addView(check, FrameLayout.LayoutParams(dp(24f), dp(24f)).apply {
                gravity = Gravity.TOP
                topMargin = dp(1f)
            })
        }
        row.addView(box, LinearLayout.LayoutParams(dp(34f), lineHeightPx))
        val text = TextView(this).apply {
            textStyle(this)
            text = b.text
            if (b.done) setTextColor(Ui.INK2)
            caretOnTap(this, i)
            if (i == note.caret) caretTextView = this
        }
        row.addView(text, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val state = Ui.chip(this, if (b.done) "сдано" else "не сдано", if (b.done) Ui.OK else Ui.BAD, if (b.done) Ui.OK_SOFT else Ui.BAD_SOFT)
        state.setOnClickListener { toggleTask(i) }
        row.addView(state, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            topMargin = dp(2f)
            marginStart = dp(8f)
        })
        return row
    }

    private fun imageView(b: Block.Image, i: Int): View {
        val maxW = min(sheet.width.takeIf { it > 0 }?.let { it - sheet.paddingLeft - sheet.paddingRight } ?: dp(600f), dp(560f))
        val bmp = bitmapCache.getOrPut(b.file) { store.loadImage(b, maxW) ?: return emptyImage(i) }
        return ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            maxWidth = maxW
            scaleType = ImageView.ScaleType.FIT_START
            setPadding(0, dp(8f), 0, dp(8f))
            contentDescription = "Картинка"
            setOnClickListener { setCaret(i) }
            setOnLongClickListener {
                AlertDialog.Builder(this@NoteActivity)
                    .setMessage("Удалить картинку из записи?")
                    .setPositiveButton("Удалить") { _, _ -> removeBlock(i) }
                    .setNegativeButton("Отмена", null)
                    .show()
                true
            }
        }
    }

    private fun emptyImage(i: Int): View = Ui.text(this, "[картинка не найдена]", 14f, Ui.MUTED).apply {
        setOnLongClickListener { removeBlock(i); true }
    }

    /** Где в строке курсора показаны вставки (серое слово + курсор): позиция и длина. */
    private var shownInsertAt = 0
    private var shownInsertLen = 0

    /** Текст текущей строки + серое промежуточное слово + мигающий курсор — в месте курсора. */
    private fun refreshCaretText() {
        val tv = caretTextView ?: return
        val b = note.blocks.getOrNull(note.caret) ?: return
        val base = blockText(b) ?: return
        val pos = caretPos(base)
        val sb = SpannableStringBuilder(base.substring(0, pos))
        if (interim.isNotEmpty()) {
            val start = sb.length
            val shown = spacedInsert(base.substring(0, pos), base.substring(pos), interim)
            sb.append(shown)
            sb.setSpan(ForegroundColorSpan(Ui.MUTED), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val c = sb.length
        sb.append("▏")
        sb.setSpan(ForegroundColorSpan(if (caretOn) Ui.ACCENT else Color.TRANSPARENT), c, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        shownInsertAt = pos
        shownInsertLen = sb.length - pos
        sb.append(base.substring(pos))
        tv.text = sb
    }

    private fun lastTextBlock(): Int {
        for (i in note.blocks.indices.reversed()) if (note.blocks[i] !is Block.Image) return i
        return note.blocks.size - 1
    }

    /** Поставить курсор в строку i; offset — место внутри строки (-1 — в конец). */
    private fun setCaret(i: Int, offset: Int = -1) {
        runAfterCommit {
            note.caret = i.coerceIn(0, note.blocks.size - 1)
            val text = blockText(note.blocks[note.caret])
            note.caretOffset = if (text == null || offset < 0 || offset >= text.length) -1 else offset
            interim = ""
            renderBlocks()
            changed(render = false)
        }
    }

    /** Нажатие пальцем на строку ставит курсор в то место слова, куда нажали. */
    private fun caretOnTap(tv: TextView, i: Int) {
        val last = FloatArray(2)
        tv.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { last[0] = e.x; last[1] = e.y }
            false
        }
        tv.setOnClickListener {
            var off = tv.getOffsetForPosition(last[0], last[1])
            if (i == note.caret && off > shownInsertAt) off = max(shownInsertAt, off - shownInsertLen)
            setCaret(i, off)
        }
    }

    /** Кнопка, которая повторяет действие, пока её держат. */
    private fun repeatOnHold(action: () -> Unit): View.OnTouchListener {
        var repeat: Runnable? = null
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    action()
                    val r = object : Runnable {
                        override fun run() {
                            action()
                            handler.postDelayed(this, 70)
                        }
                    }
                    repeat = r
                    handler.postDelayed(r, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeat?.let { handler.removeCallbacks(it) }
                    repeat = null
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------- правка текста

    private fun blockText(b: Block): String? = when (b) {
        is Block.Para -> b.text
        is Block.Task -> b.text
        else -> null
    }

    private fun setBlockText(b: Block, t: String) {
        when (b) {
            is Block.Para -> b.text = t
            is Block.Task -> b.text = t
            else -> Unit
        }
    }

    /** Позиция курсора в строке text (offset -1 или за концом — конец строки). */
    private fun caretPos(text: String): Int =
        if (note.caretOffset < 0 || note.caretOffset > text.length) text.length else note.caretOffset

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '-'

    /**
     * Что вставить у курсора с учётом пробелов. Если курсор поставлен пальцем внутрь текста
     * и вставка короткая (1–2 знака) рядом со словом — без пробелов: это исправление буквы.
     * При обычном письме в конец строки — слова через пробел («Привет» + «на» = «Привет на»).
     */
    private fun spacedInsert(before: String, after: String, word: String): String {
        if (word.isEmpty()) return word
        val placedCaret = note.caretOffset >= 0
        val touchesWord = (before.isNotEmpty() && isWordChar(before.last())) || (after.isNotEmpty() && isWordChar(after.first()))
        if (placedCaret && word.length <= 2 && word.all { isWordChar(it) } && touchesWord) return word
        val lead = before.isNotEmpty() && !before.last().isWhitespace() && word.first() !in ".,!?;:)»…"
        val trail = after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,!?;:)»…" && !word.last().isWhitespace()
        return (if (lead) " " else "") + word + (if (trail) " " else "")
    }

    /** Блок под курсором, в который можно писать текст (если курсор на картинке — новая строка после неё). */
    private fun caretTextBlock(): Block {
        val b = note.blocks.getOrNull(note.caret)
        if (b == null || b is Block.Image) {
            note.blocks.add(note.caret + 1, Block.Para(""))
            note.caret += 1
            note.caretOffset = -1
        }
        return note.blocks[note.caret]
    }

    /** Вставить текст у курсора. raw = true — как есть, без расстановки пробелов. */
    private fun insertAtCaret(word: String, raw: Boolean = false) {
        if (word.isEmpty()) return
        pushUndo()
        val b = caretTextBlock()
        val text = blockText(b) ?: return
        val pos = caretPos(text)
        val before = text.substring(0, pos)
        val after = text.substring(pos)
        val ins = if (raw) word else spacedInsert(before, after, word)
        setBlockText(b, before + ins + after)
        if (note.caretOffset >= 0) note.caretOffset = pos + ins.length
        changed()
    }

    private fun editCaret(f: (String) -> String) {
        pushUndo()
        val b = caretTextBlock()
        blockText(b)?.let { setBlockText(b, f(it)) }
        note.caretOffset = -1
        changed()
    }

    private var lastCharDelete = 0L

    /** Стереть одну букву перед курсором. В начале строки — соединить со строкой выше. */
    private fun deleteChar() {
        val b = note.blocks.getOrNull(note.caret) ?: return
        val text = blockText(b)
        val now = android.os.SystemClock.uptimeMillis()
        val coalesce = now - lastCharDelete < 1500 // серия нажатий — один шаг отмены
        lastCharDelete = now
        if (text == null) {
            hint("Курсор на картинке — нажмите на строку с текстом")
            return
        }
        val pos = caretPos(text)
        if (pos > 0) {
            if (!coalesce) pushUndo()
            val cut = text.offsetByCodePoints(pos, -1)
            setBlockText(b, text.substring(0, cut) + text.substring(pos))
            if (note.caretOffset >= 0) note.caretOffset = cut
            changed()
            return
        }
        // Курсор в начале строки: соединяем с предыдущей строкой (как на клавиатуре).
        val prevIndex = note.caret - 1
        val prev = note.blocks.getOrNull(prevIndex)
        val prevText = prev?.let { blockText(it) }
        if (prev == null || prevText == null) {
            if (text.isEmpty() && note.blocks.size > 1) {
                if (!coalesce) pushUndo()
                note.blocks.removeAt(note.caret)
                note.caret = max(0, note.caret - 1)
                note.caretOffset = -1
                changed()
            }
            return
        }
        if (!coalesce) pushUndo()
        setBlockText(prev, prevText + text)
        note.blocks.removeAt(note.caret)
        note.caret = prevIndex
        note.caretOffset = if (text.isEmpty()) -1 else prevText.length
        changed()
    }

    /** Стереть слово перед курсором (вместе с пробелами вокруг). */
    private fun deleteWord() {
        val b = note.blocks.getOrNull(note.caret)
        val text = b?.let { blockText(it) }
        if (b != null && text != null && text.isNotBlank()) {
            val pos = caretPos(text)
            val before = text.substring(0, pos).replace(Regex("""\s*\S+\s*$"""), "")
            if (before.length == pos) return
            pushUndo()
            setBlockText(b, before + text.substring(pos))
            if (note.caretOffset >= 0) note.caretOffset = before.length
            changed()
        } else if (note.blocks.size > 1) {
            pushUndo()
            note.blocks.removeAt(note.caret)
            note.caret = max(0, note.caret - 1)
            note.caretOffset = -1
            changed()
        }
    }

    /** Новая строка (или задача). Если курсор внутри строки — текст после курсора переносится. */
    private fun newLine(task: Boolean) {
        pushUndo()
        val b = note.blocks.getOrNull(note.caret)
        val text = b?.let { blockText(it) }
        if (task && b is Block.Para && b.text.isBlank()) {
            note.blocks[note.caret] = Block.Task("", false)
        } else if (b != null && text != null) {
            val pos = caretPos(text)
            setBlockText(b, text.substring(0, pos).trimEnd())
            val rest = text.substring(pos).trimStart()
            note.blocks.add(note.caret + 1, if (task) Block.Task(rest, false) else Block.Para(rest))
            note.caret += 1
            note.caretOffset = if (rest.isEmpty()) -1 else 0
        } else {
            note.blocks.add(note.caret + 1, if (task) Block.Task("", false) else Block.Para(""))
            note.caret += 1
            note.caretOffset = -1
        }
        changed()
    }

    private fun toggleTask(i: Int) {
        runAfterCommit {
            val b = note.blocks.getOrNull(i) as? Block.Task ?: return@runAfterCommit
            pushUndo()
            b.done = !b.done
            changed()
        }
    }

    private fun removeBlock(i: Int) {
        pushUndo()
        note.blocks.removeAt(i)
        if (note.blocks.isEmpty()) note.blocks += Block.Para("")
        note.caret = note.caret.coerceIn(0, note.blocks.size - 1)
        changed()
    }

    private fun editCaretDialog() {
        val b = note.blocks.getOrNull(note.caret)
        val text = when (b) { is Block.Para -> b.text; is Block.Task -> b.text; else -> null }
        if (text == null) {
            hint("Нажмите на строку с текстом, чтобы её исправить")
            return
        }
        val edit = EditText(this).apply {
            setText(text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Исправить строку")
            .setView(box)
            .setPositiveButton("Готово") { _, _ -> editCaret { edit.text.toString() } }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renameNote() {
        val edit = EditText(this).apply {
            setText(note.title)
            setSelection(note.title.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Название записи")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) {
                    pushUndo()
                    note.title = t
                    titleView.text = t
                    changed()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- отмена и сохранение

    private fun pushUndo() {
        undoStack.addLast(note.toJson().toString())
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun undo() {
        val g = current
        if (g != null && g.strokes.isNotEmpty()) {
            // Сначала убираем ещё не распознанные штрихи — по одному.
            g.strokes.removeAt(g.strokes.lastIndex)
            g.resultFor = -1
            interim = ""
            handler.removeCallbacks(interimRunnable)
            handler.removeCallbacks(commitRunnable)
            if (g.strokes.isEmpty()) current = null else handler.postDelayed(interimRunnable, 150)
            refreshCaretText()
            ink.invalidate()
            return
        }
        if (commitQueue.isNotEmpty() || undoStack.isEmpty()) return
        redoStack.addLast(note.toJson().toString())
        note = Note.fromJson(JSONObject(undoStack.removeLast()))
        titleView.text = note.title
        changed()
    }

    private fun redo() {
        if (redoStack.isEmpty() || commitQueue.isNotEmpty() || current != null) return
        undoStack.addLast(note.toJson().toString())
        note = Note.fromJson(JSONObject(redoStack.removeLast()))
        titleView.text = note.title
        changed()
    }

    private fun changed(render: Boolean = true) {
        if (render) renderBlocks()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
    }

    private fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        try {
            store.save(note)
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось сохранить: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- картинки и итоги

    private fun pickImage() {
        current?.let { commit(it) }
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "Выберите картинку"), REQ_IMAGE)
        } catch (t: Throwable) {
            hint("Не нашлось приложения для выбора картинки")
        }
    }

    @Deprecated("Activity.onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        hint("Добавляю картинку…", 1500)
        io.execute {
            val block = try { store.importImage(this, uri) } catch (t: Throwable) { null }
            handler.post {
                if (block == null) {
                    hint("Не удалось открыть картинку")
                    return@post
                }
                runAfterCommit {
                    pushUndo()
                    note.blocks.add(note.caret + 1, block)
                    note.blocks.add(note.caret + 2, Block.Para(""))
                    note.caret += 2
                    changed()
                }
            }
        }
    }

    private fun showSummary() {
        runAfterCommit {
            val tasks = note.blocks.filterIsInstance<Block.Task>().filter { it.text.isNotBlank() }
            val open = tasks.filter { !it.done }
            val done = tasks.filter { it.done }
            val text = buildString {
                append("Не сдано · ").append(open.size).append('\n')
                open.forEach { append("— ").append(it.text).append('\n') }
                if (open.isEmpty()) append("нет\n")
                append("\nСдано · ").append(done.size).append('\n')
                done.forEach { append("— ").append(it.text).append('\n') }
                if (done.isEmpty()) append("нет\n")
            }
            AlertDialog.Builder(this)
                .setTitle("Итоги записи")
                .setMessage(text + "\nЗдесь будет ИИ-помощник: кратко пересказать совещание и выписать задачи со сроками. Подключим отдельным этапом.")
                .setPositiveButton("Скопировать") { _, _ ->
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(note.title, note.title + "\n\n" + text))
                    hint("Итоги скопированы")
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    // ---------------------------------------------------------------- NoteInkView.Host

    override fun contentOffset(): PointF {
        val a = IntArray(2)
        val b = IntArray(2)
        ink.getLocationInWindow(a)
        sheet.getLocationInWindow(b)
        return PointF((b[0] - a[0]).toFloat(), (b[1] - a[1]).toFloat())
    }

    override fun forwardToSheet(ev: MotionEvent) {
        val e = MotionEvent.obtain(ev)
        scroll.dispatchTouchEvent(e)
        e.recycle()
    }

    override fun passThrough(): Boolean = tool == Tool.HAND
    override fun fingerWrites(): Boolean = fingerWrites
    override fun isEraser(): Boolean = tool == Tool.ERASER

    override fun strokeStyle(): NoteInkView.StrokeStyle = when (tool) {
        Tool.MARKER -> NoteInkView.StrokeStyle("marker", penColor, Ui.dpf(this, 16f))
        Tool.PEN -> NoteInkView.StrokeStyle("pen", penColor, Ui.dpf(this, 2.4f))
        else -> NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f))
    }

    override fun onStrokeStart(x: Float, y: Float) {
        if (tool != Tool.WRITE) return
        handler.removeCallbacks(interimRunnable)
        handler.removeCallbacks(commitRunnable)
        val g = current
        if (g != null && g.strokes.isNotEmpty() && isFar(g, x, y)) commit(g)
        if (current == null) current = Group(++groupSeq)
    }

    override fun onStrokeEnd(stroke: InkStroke, style: NoteInkView.StrokeStyle, tap: Boolean) {
        if (tap && tapOnCheckbox(stroke.points[0].x, stroke.points[0].y)) {
            if (current?.strokes?.isEmpty() == true) current = null
            if (current != null) scheduleWrite()
            return
        }
        if (style.kind == "write") {
            val g = current ?: Group(++groupSeq).also { current = it }
            g.strokes += stroke
            scheduleWrite()
        } else {
            pushUndo()
            note.drawings += NoteInkView.toDrawing(stroke, style)
            changed(render = false)
        }
        ink.invalidate()
    }

    override fun onErase(x: Float, y: Float) {
        val r = Ui.dpf(this, 14f)
        val keep = note.drawings.filterNot { d ->
            var hit = false
            var i = 0
            while (i + 1 < d.pts.size && !hit) {
                val dx = d.pts[i] - x
                val dy = d.pts[i + 1] - y
                if (dx * dx + dy * dy < (r + d.width / 2) * (r + d.width / 2)) hit = true
                i += 3
            }
            hit
        }
        if (keep.size != note.drawings.size) {
            pushUndo()
            note.drawings.clear()
            note.drawings.addAll(keep)
            changed(render = false)
            ink.invalidate()
        }
    }

    override fun drawings(): List<Drawing> = note.drawings

    override fun pendingStrokes(): List<InkStroke> {
        val out = ArrayList<InkStroke>()
        for (g in commitQueue) out += g.strokes
        current?.let { out += it.strokes }
        return out
    }

    private fun tapOnCheckbox(x: Float, y: Float): Boolean {
        for ((i, v) in blockViews.withIndex()) {
            if (note.blocks.getOrNull(i) !is Block.Task) continue
            if (y >= v.top && y <= v.bottom && x >= v.left && x <= v.left + dp(40f)) {
                toggleTask(i)
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- распознавание

    private fun scheduleWrite() {
        handler.removeCallbacks(interimRunnable)
        handler.removeCallbacks(commitRunnable)
        handler.postDelayed(interimRunnable, 180)
        handler.postDelayed(commitRunnable, pauseMs)
    }

    private fun isFar(g: Group, x: Float, y: Float): Boolean {
        val b = NoteInkView.bounds(g.strokes)
        val h = b[3] - b[1]
        val pad = max(Ui.dpf(this, 60f), h * 2.2f)
        return x < b[0] - pad || x > b[2] + pad || y < b[1] - pad || y > b[3] + pad
    }

    private fun lineHeightFor(strokes: List<InkStroke>): Float {
        val base = if (wordHeights.isNotEmpty()) wordHeights.sorted()[wordHeights.size / 2] else {
            val b = NoteInkView.bounds(strokes); b[3] - b[1]
        }
        val lo = Ui.dpf(this, 40f)
        return (base * 1.15f).coerceIn(lo, max(lo, ink.height.toFloat()))
    }

    private fun preContext(): String {
        // Текст перед курсором (до 20 знаков): строки выше + начало текущей строки до курсора.
        val sb = StringBuilder()
        for (i in 0..min(note.caret, note.blocks.size - 1)) {
            val t = blockText(note.blocks[i]) ?: continue
            val part = if (i == note.caret) t.substring(0, caretPos(t)) else t
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(part)
        }
        return sb.takeLast(20).toString()
    }

    private fun recognize(g: Group) {
        val ru = engine.language("ru")
        val n = g.strokes.size
        if (n == 0) return
        if (ru.state != HandwritingEngine.State.READY) {
            updateBanner()
            if (g.committing) {
                g.candidates = emptyList()
                g.error = "модель распознавания не скачана"
                g.resultFor = n
                flush()
            }
            return
        }
        val snapshot = ArrayList(g.strokes)
        engine.recognize(ru, snapshot, preContext(), ink.width.toFloat(), lineHeightFor(snapshot)) { out ->
            if (n < g.resultFor) return@recognize
            g.candidates = if (out.error == null) out.candidates else emptyList()
            g.error = out.error
            g.resultFor = n
            if (g === current && n == g.strokes.size) {
                interim = out.candidates.firstOrNull()?.text?.trim().orEmpty()
                refreshCaretText()
            }
            if (g.committing) flush()
        }
    }

    private fun commit(g: Group) {
        handler.removeCallbacks(commitRunnable)
        handler.removeCallbacks(interimRunnable)
        if (g === current) current = null
        if (g.strokes.isEmpty() || g.committing) return
        g.committing = true
        commitQueue.addLast(g)
        if (g.resultFor == g.strokes.size) flush() else recognize(g)
    }

    /** Вставить в текст все готовые группы по порядку; затем выполнить отложенные действия. */
    private fun flush() {
        while (commitQueue.isNotEmpty()) {
            val h = commitQueue.first()
            if (h.resultFor != h.strokes.size) break
            commitQueue.removeFirst()
            applyGroup(h)
        }
        val cur = current
        interim = if (cur != null && cur.resultFor == cur.strokes.size) cur.candidates?.firstOrNull()?.text?.trim().orEmpty() else ""
        refreshCaretText()
        ink.invalidate()
        if (commitQueue.isEmpty() && current == null) {
            while (afterCommit.isNotEmpty()) afterCommit.removeFirst().invoke()
        }
    }

    private fun applyGroup(g: Group) {
        val best = g.candidates?.firstOrNull()?.text?.trim().orEmpty()
        val style = NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f))
        if (best.isEmpty()) {
            pushUndo()
            for (s in g.strokes) note.drawings += NoteInkView.toDrawing(s, style)
            hint(g.error?.let { "Не распознано (${HandwritingEngine.shortError(it)}) — оставил рисунком" }
                ?: "Не разобрал надпись — оставил её рисунком")
            changed(render = false)
            return
        }
        val bb = NoteInkView.bounds(g.strokes)
        wordHeights += bb[3] - bb[1]
        while (wordHeights.size > 15) wordHeights.removeAt(0)
        ink.fade(g.strokes, style)
        insertAtCaret(best)
    }

    /** Выполнить действие после того, как недописанные слова станут текстом (чтобы не сбить порядок). */
    private fun runAfterCommit(action: () -> Unit) {
        current?.let { commit(it) }
        if (commitQueue.isEmpty() && current == null) action() else afterCommit.addLast(action)
    }

    companion object {
        const val EXTRA_ID = "note_id"
        private const val REQ_IMAGE = 41
    }
}
