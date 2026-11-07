package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Экран «Доска»: бесконечное поле. Пером — писать (надпись становится текстом на том же месте),
 * рисовать, стирать, ставить метки статуса. Пальцем — двигать и масштабировать, касание метки
 * переключает статус, долгое нажатие на картинку — удалить. «Рука» — двигать предметы.
 */
class BoardActivity : Activity(), BoardView.Host {

    enum class Tool { WRITE, PEN, MARKER, ERASER, HAND, STATUS }

    private lateinit var store: BoardStore
    private lateinit var noteStore: NoteStore
    private lateinit var board: Board
    private lateinit var engine: HandwritingEngine
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var root: LinearLayout
    private lateinit var view: BoardView
    private lateinit var titleView: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var hintView: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private val toolButtons = HashMap<Tool, Ui.ToolButton>()
    private val colorViews = ArrayList<Pair<Int, View>>()

    private var tool = Tool.PEN
    private var penColor = Ui.INK
    private var fingerWrites = false
    private val bitmaps = HashMap<String, Bitmap?>()
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var pauseMs = 1000L

    // ---------------------------------------------------------------- письмо в текст на месте

    private inner class Group {
        val strokes = ArrayList<InkStroke>()
        var candidates: List<Candidate>? = null
        var resultFor = -1
        var committing = false
        var error: String? = null
    }

    private var current: Group? = null
    private val committing = ArrayList<Group>()
    private val interimRunnable = Runnable { current?.let { recognize(it) } }
    private val commitRunnable = Runnable { current?.let { commit(it) } }
    private val saveRunnable = Runnable { saveNow() }
    private val bannerTicker = object : Runnable {
        override fun run() {
            updateBanner()
            if (engine.language("ru").state == HandwritingEngine.State.DOWNLOADING) handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    // ---------------------------------------------------------------- жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BoardStore(this)
        noteStore = NoteStore(this)
        val id = intent.getStringExtra(EXTRA_ID)
        board = id?.let { store.load(it) } ?: Board.create("Доска").also { store.save(it) }
        engine = HandwritingEngine { android.util.Log.i("FreeWrite", it) }
        engine.onChanged = { updateBanner() }
        buildUi()
        setContentView(root)
        engine.start(this)
        setTool(Tool.PEN)
        updateBanner()
        updateZoom()
        if (board.isEmpty && board.camX == 0f && board.camY == 0f) view.post { view.fitAll() }
    }

    override fun onPause() {
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
        current?.let { commit(it) }
        val finishNow = Runnable { saveNow(); finish() }
        if (committing.isEmpty()) finishNow.run() else handler.postDelayed(finishNow, 1500)
    }

    // ---------------------------------------------------------------- интерфейс

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.DESK) }
        val row = LinearLayout(this)
        row.addView(Ui.ToolButton(this, R.drawable.ic_back, "Назад").apply {
            minimumWidth = dp(44f)
            setOnClickListener { onBackPressed() }
        })
        titleView = Ui.text(this, board.title, 16f, Ui.INK, bold = true).apply {
            maxWidth = dp(200f); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4f), 0, dp(10f), 0)
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(Ui.mono(this@BoardActivity, "доска · бесконечное поле", 11f).apply { setPadding(dp(4f), 0, dp(10f), 0) })
            setOnClickListener { rename() }
        })
        row.addView(Ui.separator(this))
        addTool(row, Tool.WRITE, R.drawable.ic_write, "В текст")
        addTool(row, Tool.PEN, R.drawable.ic_pen, "Ручка")
        addTool(row, Tool.MARKER, R.drawable.ic_marker, "Маркер")
        addTool(row, Tool.ERASER, R.drawable.ic_eraser, "Ластик")
        addTool(row, Tool.HAND, R.drawable.ic_hand, "Рука")
        addTool(row, Tool.STATUS, R.drawable.ic_status, "Статус")
        for (c in listOf(Ui.INK, Ui.PEN_BLUE, Ui.PEN_RED)) {
            val v = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c)
                }
                contentDescription = "Цвет"
                setOnClickListener {
                    penColor = c
                    if (tool != Tool.PEN && tool != Tool.MARKER) setTool(Tool.PEN) else refreshColors()
                }
            }
            row.addView(v, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { setMargins(dp(5f), 0, dp(5f), 0) })
            colorViews += c to v
        }
        row.addView(Ui.separator(this))
        row.addView(Ui.ToolButton(this, R.drawable.ic_image, "Картинка").apply { setOnClickListener { pickImage() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_undo, "Отменить").apply { setOnClickListener { undo() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_redo, "Вернуть").apply { setOnClickListener { redo() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_fit, "Всё").apply { setOnClickListener { view.fitAll() } })
        row.addView(Ui.separator(this))
        row.addView(CheckBox(this).apply {
            text = "Палец пишет"; textSize = 13f; setTextColor(Ui.INK2)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, on -> fingerWrites = on }
        })
        root.addView(Ui.toolbar(this, row), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.addView(View(this).apply { setBackgroundColor(Ui.RULE) }, LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.WARN_SOFT); setPadding(dp(16f), dp(8f), dp(12f), dp(8f))
            visibility = View.GONE
        }
        bannerText = Ui.text(this, "", 14f, Ui.WARN)
        bannerButton = Ui.text(this, "Скачать", 14f, Color.WHITE, bold = true).apply {
            background = Ui.rounded(this@BoardActivity, Ui.ACCENT, null, 10f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                engine.download(engine.language("ru"))
                handler.removeCallbacks(bannerTicker); handler.postDelayed(bannerTicker, 1000)
            }
        }
        banner.addView(bannerText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        banner.addView(bannerButton)
        root.addView(banner, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val area = FrameLayout(this)
        view = BoardView(this, this)
        area.addView(view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        zoomLabel = Ui.mono(this, "100%", 12f, Ui.INK2).apply {
            background = Ui.rounded(this@BoardActivity, Ui.PAPER, Ui.RULE, 8f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }
        area.addView(zoomLabel, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START; setMargins(dp(14f), 0, 0, dp(14f))
        })
        hintView = Ui.text(this, "", 13f, Ui.PAPER).apply {
            background = Ui.rounded(this@BoardActivity, Ui.INK, null, 999f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f)); alpha = 0f; gravity = Gravity.CENTER
        }
        area.addView(hintView, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18f)
        })
        root.addView(area, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        handler.postDelayed({ hint("Пальцем — двигать и масштабировать, пером — писать и рисовать", 3500) }, 300)
    }

    private fun addTool(row: LinearLayout, t: Tool, icon: Int, label: String) {
        val b = Ui.ToolButton(this, icon, label).apply { setOnClickListener { setTool(t) } }
        b.tag = "board_tool_" + t.name.lowercase()
        toolButtons[t] = b
        row.addView(b)
    }

    private fun setTool(t: Tool) {
        if (t != Tool.WRITE) current?.let { commit(it) }
        tool = t
        for ((k, v) in toolButtons) v.selectedTool = k == t
        refreshColors()
        if (t == Tool.STATUS) hint("Коснитесь пером: метка «Не сдано». Пальцем по метке — следующий статус")
        if (t == Tool.HAND) hint("Перетаскивайте метки, надписи и картинки")
    }

    private fun refreshColors() {
        for ((c, v) in colorViews) {
            val sel = c == penColor && (tool == Tool.PEN || tool == Tool.MARKER)
            v.scaleX = if (sel) 1.25f else 1f; v.scaleY = v.scaleX
            v.alpha = if (tool == Tool.PEN || tool == Tool.MARKER) 1f else 0.55f
        }
    }

    private fun hint(text: String, ms: Long = 2600) {
        hintView.text = text
        hintView.animate().cancel()
        hintView.alpha = 1f
        hintView.animate().alpha(0f).setStartDelay(ms).setDuration(300).start()
    }

    private fun updateZoom() {
        if (::zoomLabel.isInitialized) zoomLabel.text = "${(board.camZ * 100).roundToInt()}%"
    }

    private fun updateBanner() {
        if (!::banner.isInitialized) return
        val ru = engine.language("ru")
        val needed = tool == Tool.WRITE
        when {
            ru.state == HandwritingEngine.State.READY || !needed -> banner.visibility = View.GONE
            ru.state == HandwritingEngine.State.DOWNLOADING -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "Русская модель: ${engine.stateText(ru)}"
                bannerButton.visibility = View.GONE
            }
            else -> {
                banner.visibility = View.VISIBLE
                bannerText.text = if (ru.state == HandwritingEngine.State.MISSING) {
                    "Для письма в текст нужна русская модель распознавания (около 20 МБ, один раз)."
                } else {
                    "Модель распознавания: ${engine.stateText(ru)}"
                }
                bannerButton.visibility = if (ru.model != null &&
                    (ru.state == HandwritingEngine.State.MISSING || ru.state == HandwritingEngine.State.ERROR)) View.VISIBLE else View.GONE
            }
        }
    }

    private fun rename() {
        val edit = EditText(this).apply { setText(board.title); setSelection(board.title.length); isSingleLine = true }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Название доски")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) { pushUndo(); board.title = t; titleView.text = t; changed() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- отмена и сохранение

    private fun pushUndo() {
        undoStack.addLast(board.toJson(withCamera = false).toString())
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restore(json: String) {
        val cam = Triple(board.camX, board.camY, board.camZ)
        board = Board.fromJson(JSONObject(json))
        board.camX = cam.first; board.camY = cam.second; board.camZ = cam.third
        titleView.text = board.title
        changed()
    }

    private fun undo() {
        val g = current
        if (g != null && g.strokes.isNotEmpty()) {
            g.strokes.removeAt(g.strokes.lastIndex)
            g.resultFor = -1
            handler.removeCallbacks(interimRunnable); handler.removeCallbacks(commitRunnable)
            if (g.strokes.isEmpty()) current = null else handler.postDelayed(interimRunnable, 150)
            view.invalidate()
            return
        }
        if (committing.isNotEmpty() || undoStack.isEmpty()) return
        redoStack.addLast(board.toJson(withCamera = false).toString())
        restore(undoStack.removeLast())
    }

    private fun redo() {
        if (redoStack.isEmpty() || committing.isNotEmpty() || current != null) return
        undoStack.addLast(board.toJson(withCamera = false).toString())
        restore(redoStack.removeLast())
    }

    private fun changed() {
        view.invalidate()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 500)
    }

    private fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        try {
            store.save(board)
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось сохранить: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- картинки

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
            val block = try { noteStore.importImage(this, uri) } catch (t: Throwable) { null }
            handler.post {
                if (block == null) { hint("Не удалось открыть картинку"); return@post }
                val z = board.camZ
                val maxW = dp(460f) / z
                val k = min(1f, maxW / block.width)
                val w = block.width * k
                val h = block.height * k
                val cx = view.wx(view.width / 2f)
                val cy = view.wy(view.height / 2f)
                pushUndo()
                board.images += BoardImage(block.file, cx - w / 2f, cy - h / 2f, w, h, block.width, block.height)
                changed()
                hint("Картинку можно двигать инструментом «Рука», удалить — долгим нажатием пальцем")
            }
        }
    }

    private fun confirmDeleteImage(i: Int) {
        AlertDialog.Builder(this)
            .setMessage("Удалить картинку с доски?")
            .setPositiveButton("Удалить") { _, _ ->
                if (i in board.images.indices) { pushUndo(); board.images.removeAt(i); changed() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- BoardView.Host

    override fun board(): Board = board
    override fun tool(): Tool = tool
    override fun fingerWrites(): Boolean = fingerWrites

    override fun strokeStyle(): NoteInkView.StrokeStyle = when (tool) {
        Tool.MARKER -> NoteInkView.StrokeStyle("marker", penColor, Ui.dpf(this, 16f))
        Tool.WRITE -> NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f))
        else -> NoteInkView.StrokeStyle("pen", penColor, Ui.dpf(this, 2.4f))
    }

    override fun onStrokeStart(x: Float, y: Float) {
        if (tool != Tool.WRITE) return
        handler.removeCallbacks(interimRunnable)
        handler.removeCallbacks(commitRunnable)
        val g = current
        if (g != null && g.strokes.isNotEmpty() && isFar(g, x, y)) commit(g)
        if (current == null) current = Group()
        updateBanner()
    }

    override fun onStrokeEnd(stroke: InkStroke, style: NoteInkView.StrokeStyle, tap: Boolean) {
        if (style.kind == "write") {
            val g = current ?: Group().also { current = it }
            g.strokes += stroke
            handler.removeCallbacks(interimRunnable); handler.removeCallbacks(commitRunnable)
            handler.postDelayed(interimRunnable, 180)
            handler.postDelayed(commitRunnable, pauseMs)
        } else {
            pushUndo()
            board.strokes += NoteInkView.toDrawing(stroke, style)
            changed()
        }
        view.invalidate()
    }

    private var eraseUndoPushed = false

    override fun onErase(x: Float, y: Float, first: Boolean) {
        if (first) eraseUndoPushed = false
        val r = Ui.dpf(this, 14f) / board.camZ
        var changedAny = false
        val keep = board.strokes.filterNot { d ->
            var i = 0
            var hit = false
            while (i + 1 < d.pts.size && !hit) {
                val dx = d.pts[i] - x; val dy = d.pts[i + 1] - y
                val rr = r + d.width / 2
                if (dx * dx + dy * dy < rr * rr) hit = true
                i += 3
            }
            hit
        }
        val h = view.hitAt(x, y)
        if (keep.size != board.strokes.size || (h != null && h.kind != BoardView.Kind.IMAGE)) {
            if (!eraseUndoPushed) { pushUndo(); eraseUndoPushed = true }
            if (keep.size != board.strokes.size) { board.strokes.clear(); board.strokes.addAll(keep); changedAny = true }
            when (h?.kind) {
                BoardView.Kind.TEXT -> { board.texts.removeAt(h.index); changedAny = true }
                BoardView.Kind.STICKER -> { board.stickers.removeAt(h.index); changedAny = true }
                else -> Unit
            }
        }
        if (changedAny) changed()
    }

    override fun onStatusTap(x: Float, y: Float) {
        val h = view.hitAt(x, y)
        pushUndo()
        if (h?.kind == BoardView.Kind.STICKER) {
            val s = board.stickers[h.index]
            s.status = (s.status + 1) % 3
        } else {
            // Метка ставится «в рост» текущего масштаба: на экране около 128×36 dp.
            val scale = resources.displayMetrics.density / board.camZ
            val s = BoardSticker(0f, 0f, 0, scale)
            s.x = x - s.w / 2f; s.y = y - s.h / 2f
            board.stickers += s
        }
        changed()
    }

    override fun onStickerTap(index: Int) {
        val s = board.stickers.getOrNull(index) ?: return
        pushUndo()
        s.status = (s.status + 1) % 3
        changed()
    }

    override fun onDragStart() { pushUndo() }
    override fun onDragEnd() { changed() }
    override fun onImageLongPress(index: Int) { confirmDeleteImage(index) }

    override fun onCameraChanged() {
        updateZoom()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 800)
    }

    override fun pendingStrokes(): List<InkStroke> {
        val out = ArrayList<InkStroke>()
        for (g in committing) out += g.strokes
        current?.let { out += it.strokes }
        return out
    }

    override fun interim(): Pair<FloatArray, String>? {
        val g = current ?: return null
        if (g.strokes.isEmpty() || g.resultFor != g.strokes.size) return null
        val text = g.candidates?.firstOrNull()?.text?.trim().orEmpty()
        if (text.isEmpty()) return null
        return NoteInkView.bounds(g.strokes) to text
    }

    override fun bitmap(img: BoardImage): Bitmap? = bitmaps.getOrPut(img.file) {
        noteStore.loadImage(Block.Image(img.file, img.pxW, img.pxH), 1600)
    }

    // ---------------------------------------------------------------- распознавание

    private fun isFar(g: Group, x: Float, y: Float): Boolean {
        val b = NoteInkView.bounds(g.strokes)
        val h = b[3] - b[1]
        val pad = max(Ui.dpf(this, 60f) / board.camZ, h * 2.2f)
        return x < b[0] - pad || x > b[2] + pad || y < b[1] - pad || y > b[3] + pad
    }

    private fun recognize(g: Group) {
        val ru = engine.language("ru")
        val n = g.strokes.size
        if (n == 0) return
        if (ru.state != HandwritingEngine.State.READY) {
            updateBanner()
            if (g.committing) {
                g.candidates = emptyList(); g.error = "модель распознавания не скачана"; g.resultFor = n
                applyGroup(g)
            }
            return
        }
        val snapshot = ArrayList(g.strokes)
        val bb = NoteInkView.bounds(snapshot)
        val h = max(bb[3] - bb[1], 1f)
        val w = max(bb[2] - bb[0], h)
        engine.recognize(ru, snapshot, "", w * 1.2f, h * 1.15f) { out ->
            if (n < g.resultFor) return@recognize
            g.candidates = if (out.error == null) out.candidates else emptyList()
            g.error = out.error
            g.resultFor = n
            if (g.committing && n == g.strokes.size) applyGroup(g) else view.invalidate()
        }
    }

    private fun commit(g: Group) {
        handler.removeCallbacks(commitRunnable)
        handler.removeCallbacks(interimRunnable)
        if (g === current) current = null
        if (g.strokes.isEmpty() || g.committing) return
        g.committing = true
        committing += g
        if (g.resultFor == g.strokes.size) applyGroup(g) else recognize(g)
    }

    /** Надпись → текст на том же месте. Не распознано — остаётся рисунком, ничего не теряется. */
    private fun applyGroup(g: Group) {
        if (!committing.remove(g)) return
        val best = g.candidates?.firstOrNull()?.text?.trim().orEmpty()
        pushUndo()
        if (best.isEmpty()) {
            val style = NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f) / board.camZ)
            for (s in g.strokes) board.strokes += NoteInkView.toDrawing(s, style)
            hint(g.error?.let { "Не распознано (${HandwritingEngine.shortError(it)}) — оставил рисунком" }
                ?: "Не разобрал надпись — оставил её рисунком")
        } else {
            val bb = NoteInkView.bounds(g.strokes)
            val h = max(bb[3] - bb[1], Ui.dpf(this, 12f) / board.camZ)
            val size = h * 0.85f
            board.texts += BoardText(bb[0], bb[1] + size * 0.85f, size, best, Ui.INK)
            view.fade(g.strokes)
        }
        changed()
    }

    companion object {
        const val EXTRA_ID = "board_id"
        private const val REQ_IMAGE = 42
    }
}
