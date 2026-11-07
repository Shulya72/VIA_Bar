package party.shulya.freewrite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/** Точка штриха: координаты в пикселях поля для письма, время в мс (часы устройства), нажим 0..1. */
class InkPoint(val x: Float, val y: Float, val t: Long, val pressure: Float)

/** Один штрих — от касания пера до отрыва. */
class InkStroke(val toolType: Int) {
    val points = ArrayList<InkPoint>()
    val path = Path()
}

/**
 * Поле для свободного письма.
 *
 * Получает «сырые» события касания Android (MotionEvent), сохраняет штрихи с координатами
 * и временем каждой точки и сразу рисует их. По умолчанию пишет только перо
 * (TOOL_TYPE_STYLUS): палец и ладонь не оставляют следа.
 */
@SuppressLint("ViewConstructor")
class InkCanvasView(context: Context) : View(context) {

    interface Listener {
        fun onStrokeStarted()
        fun onStrokeFinished()
        fun onTouchIgnored(toolType: Int)
        fun onInputChanged()
    }

    var listener: Listener? = null

    /** false — пишет только перо; true — пишет и палец (на случай, если система не отличает перо). */
    var allowFinger = false

    var hint = "Пишите пером здесь — в любом месте"
        set(value) {
            field = value
            invalidate()
        }

    val stats = StylusStats()

    private val strokes = ArrayList<InkStroke>()
    private var current: InkStroke? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var clockOffset = 0L

    private val density = resources.displayMetrics.density

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(26, 32, 56)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.6f * density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 47, 75, 124)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 47, 75, 124)
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var dots = FloatArray(0)

    val strokeCount: Int get() = strokes.size

    /** Копия списка готовых штрихов (текущий, ещё не законченный, не входит). */
    fun snapshot(): List<InkStroke> = ArrayList(strokes)

    fun clear() {
        strokes.clear()
        current = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    fun undoLast(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.lastIndex)
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Лёгкая точечная сетка — только чтобы было видно границы поля. Писать по ней не нужно.
        val step = 32f * density
        val list = ArrayList<Float>()
        var y = step
        while (y < h) {
            var x = step
            while (x < w) {
                list.add(x)
                list.add(y)
                x += step
            }
            y += step
        }
        dots = list.toFloatArray()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dots.isNotEmpty()) canvas.drawPoints(dots, dotPaint)
        for (s in strokes) canvas.drawPath(s.path, inkPaint)
        current?.let { canvas.drawPath(it.path, inkPaint) }
        if (strokes.isEmpty() && current == null && hint.isNotEmpty()) {
            canvas.drawText(hint, width / 2f, height / 2f, hintPaint)
        }
    }

    private fun isPen(tool: Int) =
        tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    private fun accepts(tool: Int) = isPen(tool) || allowFinger

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Всегда возвращаем true: если первой легла ладонь, перо придёт вторым касанием
        // того же жеста, и мы не должны его потерять.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val tool = event.getToolType(index)
                stats.onDown(tool)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Получать точки без группировки по кадрам — меньше задержка.
                    requestUnbufferedDispatch(event)
                }
                val cur = current
                when {
                    !accepts(tool) -> {
                        stats.touchesIgnored++
                        listener?.onTouchIgnored(tool)
                    }
                    cur == null -> startStroke(event, index, tool)
                    isPen(tool) && !isPen(cur.toolType) -> {
                        // Сначала легла ладонь или палец, потом перо: след ладони выбрасываем.
                        stats.palmSwaps++
                        startStroke(event, index, tool)
                    }
                    else -> stats.touchesIgnored++
                }
                listener?.onInputChanged()
            }

            MotionEvent.ACTION_MOVE -> {
                val cur = current ?: return true
                val pi = event.findPointerIndex(activePointerId)
                if (pi < 0) return true
                // Между кадрами система копит промежуточные точки (history) — берём все.
                for (h in 0 until event.historySize) {
                    addPoint(
                        cur,
                        event.getHistoricalX(pi, h),
                        event.getHistoricalY(pi, h),
                        event.getHistoricalEventTime(h),
                        event.getHistoricalPressure(pi, h),
                    )
                }
                addPoint(cur, event.getX(pi), event.getY(pi), event.eventTime, event.getPressure(pi))
                stats.onSample(event, pi)
                invalidate()
                listener?.onInputChanged()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val cur = current
                val index = event.actionIndex
                if (cur != null && event.getPointerId(index) == activePointerId) {
                    addPoint(cur, event.getX(index), event.getY(index), event.eventTime, event.getPressure(index))
                    finishStroke(cur)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (current != null) {
                    current = null
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    stats.canceledStrokes++
                    invalidate()
                    listener?.onInputChanged()
                }
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        // Перо над экраном без касания. Только считаем — это ответ на вопрос «видит ли система наведение».
        if (isPen(event.getToolType(0))) {
            stats.hoverEvents++
            listener?.onInputChanged()
        }
        return true
    }

    private fun startStroke(event: MotionEvent, index: Int, tool: Int) {
        clockOffset = System.currentTimeMillis() - SystemClock.uptimeMillis()
        val s = InkStroke(tool)
        val x = event.getX(index)
        val y = event.getY(index)
        s.points.add(InkPoint(x, y, event.eventTime + clockOffset, event.getPressure(index)))
        s.path.moveTo(x, y)
        s.path.lineTo(x + 0.01f, y) // точку (например, над «ё») видно сразу
        current = s
        activePointerId = event.getPointerId(index)
        stats.onSample(event, index)
        invalidate()
        listener?.onStrokeStarted()
    }

    private fun addPoint(s: InkStroke, x: Float, y: Float, uptime: Long, pressure: Float) {
        val last = s.points[s.points.lastIndex]
        if (last.x == x && last.y == y) return
        s.points.add(InkPoint(x, y, uptime + clockOffset, pressure))
        // Сглаживание: кривая через середины отрезков.
        s.path.quadTo(last.x, last.y, (last.x + x) / 2f, (last.y + y) / 2f)
    }

    private fun finishStroke(s: InkStroke) {
        val last = s.points[s.points.lastIndex]
        s.path.lineTo(last.x, last.y)
        strokes.add(s)
        current = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        stats.onStrokeDone(s)
        invalidate()
        listener?.onStrokeFinished()
    }
}
