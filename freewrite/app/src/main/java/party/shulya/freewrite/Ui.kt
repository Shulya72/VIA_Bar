package party.shulya.freewrite

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Цвета и мелкие помощники интерфейса — по макету «рабочий журнал». */
object Ui {
    val DESK = Color.rgb(230, 234, 240)
    val PAPER = Color.rgb(252, 253, 254)
    val INK = Color.rgb(22, 34, 58)
    val INK2 = Color.rgb(75, 88, 115)
    val MUTED = Color.rgb(133, 146, 168)
    val RULE = Color.rgb(211, 218, 229)
    val LINE = Color.argb(30, 40, 80, 140)
    val MARGIN = Color.argb(56, 212, 71, 12)
    val ACCENT = Color.rgb(212, 71, 12)
    val ACCENT_SOFT = Color.rgb(253, 230, 216)
    val PEN_BLUE = Color.rgb(31, 79, 209)
    val PEN_RED = Color.rgb(194, 40, 43)
    val OK = Color.rgb(35, 128, 58)
    val OK_SOFT = Color.rgb(225, 242, 228)
    val BAD = Color.rgb(190, 42, 42)
    val BAD_SOFT = Color.rgb(251, 226, 226)
    val WARN = Color.rgb(168, 106, 16)
    val WARN_SOFT = Color.rgb(252, 240, 212)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, fill: Int, stroke: Int? = null, radiusDp: Float = 12f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(ctx, radiusDp)
            if (stroke != null) setStroke(dp(ctx, 1f), stroke)
        }

    fun text(ctx: Context, value: String, sp: Float, color: Int = INK, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun mono(ctx: Context, value: String, sp: Float, color: Int = MUTED): TextView =
        text(ctx, value, sp, color).apply { typeface = Typeface.MONOSPACE }

    /** Кнопка инструмента: значок и подпись под ним. Выбранная — с оранжевым фоном. */
    class ToolButton(ctx: Context, iconRes: Int, label: String) : LinearLayout(ctx) {
        private val icon = ImageView(ctx)
        private val caption = TextView(ctx)
        var selectedTool = false
            set(value) {
                field = value
                val c = if (value) ACCENT else INK2
                icon.imageTintList = ColorStateList.valueOf(c)
                caption.setTextColor(c)
                background = if (value) rounded(context, ACCENT_SOFT, null, 10f) else null
            }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 7f), dp(ctx, 5f), dp(ctx, 7f), dp(ctx, 4f))
            minimumWidth = dp(ctx, 54f)
            isClickable = true
            isFocusable = true
            contentDescription = label
            icon.setImageResource(iconRes)
            addView(icon, LayoutParams(dp(ctx, 24f), dp(ctx, 24f)))
            caption.text = label
            caption.textSize = 11f
            caption.gravity = Gravity.CENTER
            addView(caption, LayoutParams(WRAP, WRAP))
            selectedTool = false
        }
    }

    fun separator(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(RULE)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 1f), MATCH).apply {
            setMargins(dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f))
        }
    }

    /** Полоса инструментов, которая прокручивается вбок на узких экранах. */
    fun toolbar(ctx: Context, row: LinearLayout): HorizontalScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(PAPER)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f))
        addView(row, ViewGroup.LayoutParams(WRAP, WRAP))
    }

    fun chip(ctx: Context, value: String, fg: Int, bg: Int): TextView = text(ctx, value, 12f, fg).apply {
        background = rounded(ctx, bg, null, 999f)
        setPadding(dp(ctx, 8f), dp(ctx, 2f), dp(ctx, 8f), dp(ctx, 2f))
    }

    // ---------------------------------------------------------------- даты

    private val ru = Locale("ru")

    fun dayTitle(time: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = time }
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        val dayDiff = (startOfDay(now.timeInMillis) - startOfDay(time)) / 86_400_000L
        val date = SimpleDateFormat(if (sameYear) "d MMMM, EEEE" else "d MMMM yyyy", ru).format(Date(time))
        return when (dayDiff) {
            0L -> "Сегодня · $date"
            1L -> "Вчера · $date"
            else -> date
        }
    }

    fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun time(time: Long): String = SimpleDateFormat("HH:mm", ru).format(Date(time))

    fun longDate(time: Long): String = SimpleDateFormat("EEEE, d MMMM", ru).format(Date(time))
        .replaceFirstChar { it.uppercase() }

    fun noteMeta(time: Long): String = SimpleDateFormat("d MMMM yyyy, HH:mm", ru).format(Date(time))
}
