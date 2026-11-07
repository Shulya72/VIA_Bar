package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Ежедневник: записи по дням, «Новая запись», проверка планшета и обновление. */
class HomeActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var boards: BoardStore
    private lateinit var list: LinearLayout
    private lateinit var updateBar: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateButton: TextView
    private var release: Updater.Release? = null
    private var downloaded: File? = null

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        boards = BoardStore(this)
        setContentView(buildUi())
        checkUpdates(silent = true)
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.DESK); isFillViewport = true }
        val outer = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(22f), dp(20f), dp(32f))
        }
        outer.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.addView(outer)

        // Шапка
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val brand = SpannableString("FreeWrite").apply {
            setSpan(ForegroundColorSpan(Ui.ACCENT), 4, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        head.addView(Ui.text(this, "", 30f, Ui.INK, bold = true).apply { text = brand }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.mono(this, Ui.longDate(System.currentTimeMillis()), 14f, Ui.INK2).apply { setPadding(0, 0, 0, dp(6f)) })
        col.addView(head)

        // Плашка обновления
        updateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@HomeActivity, Ui.ACCENT_SOFT, null, 12f)
            setPadding(dp(14f), dp(10f), dp(10f), dp(10f))
            visibility = View.GONE
        }
        updateText = Ui.text(this, "", 14f, Ui.ACCENT)
        updateButton = Ui.text(this, "Обновить", 14f, Color.WHITE, bold = true).apply {
            background = Ui.rounded(this@HomeActivity, Ui.ACCENT, null, 10f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener { startUpdate() }
        }
        updateBar.addView(updateText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        updateBar.addView(updateButton)
        col.addView(updateBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(14f) })

        // Две большие кнопки
        val starts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        starts.addView(startCard(R.drawable.ic_note, "Новая запись",
            "Совещание, планёрка, звонок. Пишете пером где угодно — слова сами собираются в текст.", true) { newNote() },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginEnd = dp(7f) })
        starts.addView(startCard(R.drawable.ic_board, "Новая доска",
            "Бесконечное поле: картинки, схемы, надписи, метки «сдано / не сдано». Пальцем двигаете, пером пишете.", true) { newBoard() },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = dp(7f) })
        col.addView(starts, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(18f) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10f) })

        // Низ: проверка планшета, обновления, версия
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        foot.addView(Ui.ToolButton(this, R.drawable.ic_pulse, "Проверка планшета").apply {
            setOnClickListener { startActivity(Intent(this@HomeActivity, DiagnosticsActivity::class.java)) }
        })
        foot.addView(Ui.ToolButton(this, R.drawable.ic_update, "Обновления").apply {
            setOnClickListener { checkUpdates(silent = false) }
        })
        foot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        foot.addView(Ui.mono(this, "версия ${BuildConfig.VERSION_NAME}", 12f))
        col.addView(foot, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(26f) })
        return scroll
    }

    private fun startCard(icon: Int, title: String, text: String, enabled: Boolean, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@HomeActivity, Ui.PAPER, Ui.RULE, 16f)
            setPadding(dp(16f), dp(16f), dp(16f), dp(14f))
            isClickable = enabled
            alpha = if (enabled) 1f else 0.6f
            setOnClickListener { if (enabled) onClick() }
        }
        val ico = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Ui.ACCENT)
            background = Ui.rounded(this@HomeActivity, Ui.ACCENT_SOFT, null, 12f)
            setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        }
        card.addView(ico, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        card.addView(Ui.text(this, title, 19f, Ui.INK, bold = true).apply { setPadding(0, dp(10f), 0, dp(4f)) })
        card.addView(Ui.text(this, text, 14f, Ui.INK2))
        return card
    }

    private fun renderList() {
        list.removeAllViews()
        // Записи и доски вместе, по дням, новые сверху.
        val entries: List<Pair<Long, () -> View>> =
            store.list().map { n -> n.created to { noteRow(n) } } +
                boards.list().map { b -> b.created to { boardRow(b) } }
        if (entries.isEmpty()) {
            list.addView(Ui.text(this, "Записей пока нет. Нажмите «Новая запись» и пишите пером в любом месте листа.", 15f, Ui.INK2).apply {
                setPadding(dp(2f), dp(18f), dp(2f), dp(4f))
            })
            return
        }
        var lastDay = -1L
        for ((created, row) in entries.sortedByDescending { it.first }) {
            val day = Ui.startOfDay(created)
            if (day != lastDay) {
                lastDay = day
                list.addView(Ui.text(this, Ui.dayTitle(created).uppercase(), 12f, Ui.MUTED, bold = true).apply {
                    letterSpacing = 0.06f
                    setPadding(dp(2f), dp(18f), 0, dp(8f))
                })
            }
            list.addView(row(), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8f) })
        }
    }

    private fun boardRow(b: Board): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@HomeActivity, Ui.PAPER, Ui.RULE, 12f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            setOnClickListener { openBoard(b.id) }
            setOnLongClickListener { boardMenu(b); true }
        }
        row.addView(Ui.mono(this, Ui.time(b.created), 13f, Ui.INK2), LinearLayout.LayoutParams(dp(58f), Ui.WRAP))
        val what = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        what.addView(Ui.text(this, b.title, 16f, Ui.INK, bold = true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
        val items = b.strokes.size + b.texts.size + b.images.size + b.stickers.size
        what.addView(Ui.text(this, if (items == 0) "доска · пустая" else "доска · предметов: $items", 13f, Ui.MUTED))
        row.addView(what, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (b.count(2) > 0) row.addView(Ui.chip(this, "${b.count(2)} сдано", Ui.OK, Ui.OK_SOFT), chipParams())
        if (b.count(1) > 0) row.addView(Ui.chip(this, "${b.count(1)} в работе", Ui.WARN, Ui.WARN_SOFT), chipParams())
        if (b.count(0) > 0) row.addView(Ui.chip(this, "${b.count(0)} не сдано", Ui.BAD, Ui.BAD_SOFT), chipParams())
        return row
    }

    private fun openBoard(id: String) {
        startActivity(Intent(this, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, id))
    }

    private fun newBoard() {
        val b = Board.create("Доска")
        boards.save(b)
        openBoard(b.id)
    }

    private fun boardMenu(b: Board) {
        AlertDialog.Builder(this)
            .setTitle(b.title)
            .setItems(arrayOf("Переименовать", "Удалить")) { _, which ->
                if (which == 0) {
                    val edit = EditText(this).apply { setText(b.title); setSelection(b.title.length); isSingleLine = true }
                    val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
                    AlertDialog.Builder(this)
                        .setTitle("Название доски")
                        .setView(box)
                        .setPositiveButton("Готово") { _, _ ->
                            val t = edit.text.toString().trim()
                            if (t.isNotEmpty()) { b.title = t; boards.save(b); renderList() }
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Удалить доску «${b.title}»? Вернуть её будет нельзя.")
                        .setPositiveButton("Удалить") { _, _ -> boards.delete(b); renderList() }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .show()
    }

    private fun noteRow(n: Note): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@HomeActivity, Ui.PAPER, Ui.RULE, 12f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            setOnClickListener { open(n.id) }
            setOnLongClickListener { noteMenu(n); true }
        }
        row.addView(Ui.mono(this, Ui.time(n.created), 13f, Ui.INK2), LinearLayout.LayoutParams(dp(58f), Ui.WRAP))
        val what = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        what.addView(Ui.text(this, n.title, 16f, Ui.INK, bold = true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
        val preview = n.preview
        if (preview.isNotEmpty()) {
            what.addView(Ui.text(this, preview, 13f, Ui.MUTED).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        row.addView(what, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (n.tasksDone > 0) row.addView(Ui.chip(this, "${n.tasksDone} сдано", Ui.OK, Ui.OK_SOFT), chipParams())
        if (n.tasksOpen > 0) row.addView(Ui.chip(this, "${n.tasksOpen} не сдано", Ui.BAD, Ui.BAD_SOFT), chipParams())
        return row
    }

    private fun chipParams() = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(6f) }

    private fun open(id: String) {
        startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id))
    }

    private fun newNote() {
        val n = Note.create("Совещание")
        store.save(n)
        open(n.id)
    }

    private fun noteMenu(n: Note) {
        AlertDialog.Builder(this)
            .setTitle(n.title)
            .setItems(arrayOf("Переименовать", "Удалить")) { _, which ->
                if (which == 0) rename(n) else confirmDelete(n)
            }
            .show()
    }

    private fun rename(n: Note) {
        val edit = EditText(this).apply { setText(n.title); setSelection(n.title.length); isSingleLine = true }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Название записи")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) { n.title = t; store.save(n); renderList() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDelete(n: Note) {
        AlertDialog.Builder(this)
            .setMessage("Удалить запись «${n.title}»? Вернуть её будет нельзя.")
            .setPositiveButton("Удалить") { _, _ -> store.delete(n); renderList() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- обновление

    private fun checkUpdates(silent: Boolean) {
        if (!silent) Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        Updater.check { r, err ->
            if (isFinishing) return@check
            if (r != null && Updater.isNewer(r.version, BuildConfig.VERSION_NAME)) {
                release = r
                downloaded = null
                updateText.text = "Доступна новая версия ${r.version} (у вас ${BuildConfig.VERSION_NAME})"
                updateButton.text = "Обновить"
                updateButton.isEnabled = true
                updateBar.visibility = View.VISIBLE
            } else if (!silent) {
                val msg = err?.let { "Не удалось проверить: $it" } ?: "У вас последняя версия ${BuildConfig.VERSION_NAME}"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startUpdate() {
        downloaded?.let { if (it.exists()) { install(it); return } }
        val r = release ?: return
        updateButton.isEnabled = false
        updateText.text = "Скачиваю версию ${r.version}…"
        Updater.download(this, r, progress = { pct ->
            updateText.text = "Скачиваю версию ${r.version}… $pct%"
        }) { file, err ->
            updateButton.isEnabled = true
            if (file == null) {
                updateText.text = "Не удалось скачать: $err"
                updateButton.text = "Ещё раз"
                return@download
            }
            downloaded = file
            updateText.text = "Версия ${r.version} скачана"
            updateButton.text = "Установить"
            install(file)
        }
    }

    private fun install(file: File) {
        if (!Updater.install(this, file)) {
            Toast.makeText(this, "Разрешите FreeWrite устанавливать приложения, вернитесь и нажмите «Установить»", Toast.LENGTH_LONG).show()
        }
    }
}
