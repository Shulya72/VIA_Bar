package party.shulya.freewrite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Задать высоту строки текста ровно в px (чтобы текст ложился на линовку). */
fun TextView.setExactLineHeight(px: Int) {
    if (Build.VERSION.SDK_INT >= 28) {
        lineHeight = px
    } else {
        val fm = paint.fontMetricsInt
        setLineSpacing((px - (fm.descent - fm.ascent)).toFloat(), 1f)
    }
    includeFontPadding = false
}

/**
 * Лист записи: вертикальный список блоков на бумаге с линовкой.
 * Линии рисуются точно под строками текста (у видов с тегом RULED) и продолжаются
 * с шагом lineGap ниже последней строки — как в бумажном ежедневнике.
 */
class RuledSheet(context: Context) : LinearLayout(context) {
    var lineGap = Ui.dpf(context, 36f)
    private val linePaint = Paint().apply { color = Ui.LINE; strokeWidth = Ui.dpf(context, 1f) }
    private val marginPaint = Paint().apply { color = Ui.MARGIN; strokeWidth = Ui.dpf(context, 1f) }
    private val under = Ui.dpf(context, 4f)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft - Ui.dpf(context, 10f)
        val right = (width - paddingRight).toFloat() + Ui.dpf(context, 10f)
        val marginX = paddingLeft - Ui.dpf(context, 16f)
        canvas.drawLine(marginX, 0f, marginX, height.toFloat(), marginPaint)
        var lastY = -1f
        forEachRuledText(this, 0f) { tv, top ->
            val layout = tv.layout ?: return@forEachRuledText
            val descent = tv.paint.fontMetrics.descent
            for (i in 0 until layout.lineCount) {
                val y = top + tv.paddingTop + layout.getLineBaseline(i) + descent + under
                canvas.drawLine(left, y, right, y, linePaint)
                lastY = max(lastY, y)
            }
        }
        var y = if (lastY < 0) paddingTop + lineGap else lastY + lineGap
        while (y < height - paddingBottom / 2f) {
            canvas.drawLine(left, y, right, y, linePaint)
            y += lineGap
        }
    }

    private fun forEachRuledText(group: ViewGroup, offsetY: Float, fn: (TextView, Float) -> Unit) {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.visibility != VISIBLE) continue
            val top = offsetY + c.top
            if (c is TextView && c.tag == RULED) fn(c, top)
            else if (c is ViewGroup) forEachRuledText(c, top, fn)
        }
    }

    companion object {
        const val RULED = "ruled"
    }
}

/**
 * Прозрачный слой пера поверх листа.
 *
 * Перо (и мышь) рисуют здесь. Касания пальцем передаются листу под слоем: так работают прокрутка
 * с инерцией, нажатия на галочки и перенос курсора. Если сначала легла ладонь, а потом коснулось
 * перо, жест ладони отменяется и начинается штрих пера.
 * Координаты штрихов — в системе листа (контента), чтобы рисунок двигался вместе с текстом.
 */
@SuppressLint("ViewConstructor")
class NoteInkView(context: Context, private val host: Host) : View(context) {

    interface Host {
        /** Где начало листа относительно этого слоя (учитывает прокрутку). */
        fun contentOffset(): PointF
        /** Передать касание пальца листу. */
        fun forwardToSheet(ev: MotionEvent)
        fun passThrough(): Boolean
        fun fingerWrites(): Boolean
        fun isEraser(): Boolean
        fun strokeStyle(): StrokeStyle
        fun onStrokeStart(x: Float, y: Float)
        fun onStrokeEnd(stroke: InkStroke, style: StrokeStyle, tap: Boolean)
        fun onErase(x: Float, y: Float)
        fun drawings(): List<Drawing>
        fun pendingStrokes(): List<InkStroke>
    }

    class StrokeStyle(val kind: String, val color: Int, val width: Float)

    private enum class Mode { NONE, FORWARD, INK, ERASE }

    private var mode = Mode.NONE
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var live: InkStroke? = null
    private var liveStyle: StrokeStyle? = null
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var clockOffset = 0L
    private var hover: PointF? = null
    private val fading = ArrayList<Pair<List<InkStroke>, Long>>()
    private var fadingStyle = StrokeStyle("pen", Ui.INK, Ui.dpf(context, 2.6f))

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // Перо над экраном: маленькая точка цвета чернил (как у пера Samsung),
    // у ластика — тонкий серый круг размером со стирающую область.
    private val hoverDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hoverRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 133, 146, 168)
        strokeWidth = Ui.dpf(context, 1f)
    }

    init {
        // Не показывать системный указатель пера поверх нашего — иначе два курсора.
        if (Build.VERSION.SDK_INT >= 24) {
            pointerIcon = android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL)
        }
    }
    private val path = Path()

    /** Плавно убрать штрихи, которые стали текстом. */
    fun fade(strokes: List<InkStroke>, style: StrokeStyle) {
        fadingStyle = style
        fading += strokes to SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    private fun inks(tool: Int): Boolean =
        !host.passThrough() && (isPen(tool) || tool == MotionEvent.TOOL_TYPE_MOUSE || host.fingerWrites())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = ev.getToolType(0)
                if (inks(tool)) {
                    startInk(ev, 0)
                } else {
                    mode = Mode.FORWARD
                    host.forwardToSheet(ev)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                if ((mode == Mode.FORWARD || mode == Mode.NONE) && isPen(ev.getToolType(idx)) && !host.passThrough()) {
                    // Ладонь легла раньше пера (или осталась лежать после прошлого штриха):
                    // отменяем жест ладони, пишем пером.
                    if (mode == Mode.FORWARD) {
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        host.forwardToSheet(cancel)
                        cancel.recycle()
                    }
                    startInk(ev, idx)
                } else if (mode == Mode.FORWARD) {
                    host.forwardToSheet(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.FORWARD -> host.forwardToSheet(ev)
                Mode.INK, Mode.ERASE -> moveInk(ev)
                Mode.NONE -> Unit
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.FORWARD) {
                    host.forwardToSheet(ev)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) mode = Mode.NONE
                } else if ((mode == Mode.INK || mode == Mode.ERASE) && ev.getPointerId(ev.actionIndex) == activePointer) {
                    finishInk(ev, ev.actionIndex)
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP) mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.FORWARD) host.forwardToSheet(ev)
                live = null
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isPen(event.getToolType(0)) || host.passThrough()) return super.onHoverEvent(event)
        hover = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else PointF(event.x, event.y)
        invalidate()
        return true
    }

    private fun startInk(ev: MotionEvent, idx: Int) {
        parent?.requestDisallowInterceptTouchEvent(true)
        requestUnbufferedDispatch(ev)
        activePointer = ev.getPointerId(idx)
        val o = host.contentOffset()
        val x = ev.getX(idx) - o.x
        val y = ev.getY(idx) - o.y
        downTime = SystemClock.uptimeMillis()
        downX = ev.getX(idx)
        downY = ev.getY(idx)
        hover = null
        if (host.isEraser() || ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER) {
            mode = Mode.ERASE
            host.onErase(x, y)
            return
        }
        mode = Mode.INK
        host.onStrokeStart(x, y)
        clockOffset = System.currentTimeMillis() - SystemClock.uptimeMillis()
        val s = InkStroke(ev.getToolType(idx))
        s.points.add(InkPoint(x, y, ev.eventTime + clockOffset, pressureOf(ev, idx, -1)))
        live = s
        liveStyle = host.strokeStyle()
        invalidate()
    }

    private fun pressureOf(ev: MotionEvent, idx: Int, h: Int): Float {
        if (!isPen(ev.getToolType(idx))) return 0.5f
        val p = if (h < 0) ev.getPressure(idx) else ev.getHistoricalPressure(idx, h)
        // На MatePad Mini нажим доходит примерно до 0,6 — растягиваем до 0..1.
        return (p / 0.6f).coerceIn(0f, 1f)
    }

    private fun moveInk(ev: MotionEvent) {
        val idx = ev.findPointerIndex(activePointer)
        if (idx < 0) return
        val o = host.contentOffset()
        if (mode == Mode.ERASE) {
            for (h in 0 until ev.historySize) host.onErase(ev.getHistoricalX(idx, h) - o.x, ev.getHistoricalY(idx, h) - o.y)
            host.onErase(ev.getX(idx) - o.x, ev.getY(idx) - o.y)
            return
        }
        val s = live ?: return
        for (h in 0 until ev.historySize) {
            add(s, ev.getHistoricalX(idx, h) - o.x, ev.getHistoricalY(idx, h) - o.y, ev.getHistoricalEventTime(h), pressureOf(ev, idx, h))
        }
        add(s, ev.getX(idx) - o.x, ev.getY(idx) - o.y, ev.eventTime, pressureOf(ev, idx, -1))
        invalidate()
    }

    private fun add(s: InkStroke, x: Float, y: Float, t: Long, p: Float) {
        val last = s.points[s.points.lastIndex]
        if (last.x == x && last.y == y) return
        s.points.add(InkPoint(x, y, t + clockOffset, p))
    }

    private fun finishInk(ev: MotionEvent, idx: Int) {
        if (mode == Mode.ERASE) {
            mode = Mode.NONE
            return
        }
        val s = live ?: return
        val o = host.contentOffset()
        add(s, ev.getX(idx) - o.x, ev.getY(idx) - o.y, ev.eventTime, pressureOf(ev, idx, -1))
        val tap = SystemClock.uptimeMillis() - downTime < 280 && hypot(ev.getX(idx) - downX, ev.getY(idx) - downY) < Ui.dpf(context, 4f)
        live = null
        mode = Mode.NONE
        host.onStrokeEnd(s, liveStyle ?: host.strokeStyle(), tap)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val o = host.contentOffset()
        canvas.save()
        canvas.translate(o.x, o.y)
        for (d in host.drawings()) drawDrawing(canvas, d)
        val pendingStyle = StrokeStyle("write", Ui.INK, Ui.dpf(context, 2.6f))
        for (s in host.pendingStrokes()) drawStroke(canvas, s, pendingStyle, 255)
        live?.let { drawStroke(canvas, it, liveStyle ?: pendingStyle, 255) }
        val now = SystemClock.uptimeMillis()
        fading.removeAll { now - it.second > FADE_MS }
        for ((strokes, t0) in fading) {
            val a = (255 * (1f - (now - t0).toFloat() / FADE_MS)).toInt().coerceIn(0, 255)
            for (s in strokes) drawStroke(canvas, s, fadingStyle, a)
        }
        canvas.restore()
        hover?.let {
            if (host.isEraser()) {
                canvas.drawCircle(it.x, it.y, Ui.dpf(context, 14f), hoverRing)
            } else {
                hoverDot.color = host.strokeStyle().color
                hoverDot.alpha = 170
                canvas.drawCircle(it.x, it.y, Ui.dpf(context, 2.5f), hoverDot)
            }
        }
        if (fading.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawStroke(canvas: Canvas, s: InkStroke, style: StrokeStyle, alpha: Int) {
        val pts = s.points
        if (pts.isEmpty()) return
        paint.color = style.color
        paint.alpha = if (style.kind == "marker") alpha * 80 / 255 else alpha
        if (pts.size == 1 || style.kind != "pen") {
            paint.strokeWidth = style.width
            if (pts.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(pts[0].x, pts[0].y, style.width * 0.6f, paint)
                paint.style = Paint.Style.STROKE
                return
            }
            path.reset()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                path.quadTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
            }
            path.lineTo(pts[pts.size - 1].x, pts[pts.size - 1].y)
            canvas.drawPath(path, paint)
            return
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = style.width * (0.45f + 1.1f * (a.pressure + b.pressure) / 2f)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }

    private fun drawDrawing(canvas: Canvas, d: Drawing) {
        val pts = d.pts
        val n = pts.size / 3
        if (n == 0) return
        paint.color = d.color
        paint.alpha = if (d.kind == "marker") 80 else 255
        if (n == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pts[0], pts[1], d.width * 0.6f, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        if (d.kind == "pen") {
            for (i in 1 until n) {
                paint.strokeWidth = d.width * (0.45f + 1.1f * (pts[i * 3 - 1] + pts[i * 3 + 2]) / 2f)
                canvas.drawLine(pts[i * 3 - 3], pts[i * 3 - 2], pts[i * 3], pts[i * 3 + 1], paint)
            }
        } else {
            paint.strokeWidth = d.width
            path.reset()
            path.moveTo(pts[0], pts[1])
            for (i in 1 until n) {
                val ax = pts[i * 3 - 3]; val ay = pts[i * 3 - 2]
                val bx = pts[i * 3]; val by = pts[i * 3 + 1]
                path.quadTo(ax, ay, (ax + bx) / 2f, (ay + by) / 2f)
            }
            path.lineTo(pts[(n - 1) * 3], pts[(n - 1) * 3 + 1])
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        const val FADE_MS = 260L

        fun toDrawing(s: InkStroke, style: StrokeStyle): Drawing {
            val arr = FloatArray(s.points.size * 3)
            s.points.forEachIndexed { i, p ->
                arr[i * 3] = p.x; arr[i * 3 + 1] = p.y; arr[i * 3 + 2] = p.pressure
            }
            val kind = if (style.kind == "write") "pen" else style.kind
            return Drawing(kind, style.color, style.width, arr)
        }

        /** Рамка штрихов: [left, top, right, bottom]. */
        fun bounds(strokes: List<InkStroke>): FloatArray {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (s in strokes) for (p in s.points) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
            return floatArrayOf(l, t, r, b)
        }
    }
}
