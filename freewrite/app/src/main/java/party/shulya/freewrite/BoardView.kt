package party.shulya.freewrite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Поле доски: бесконечная плоскость с камерой (сдвиг + масштаб).
 *
 * Перо (и мышь) — инструмент: писать, рисовать, стирать, ставить метки.
 * Палец — навигация: один палец двигает доску, два — масштабируют; короткое касание метки
 * переключает её статус; долгое нажатие на картинку — удалить. Инструмент «Рука» двигает предметы.
 * Если сначала легла ладонь, а потом коснулось перо, — работает перо.
 */
@SuppressLint("ViewConstructor")
class BoardView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun board(): Board
        fun tool(): BoardActivity.Tool
        fun fingerWrites(): Boolean
        /** Стиль штриха в пикселях экрана (BoardView переводит в единицы доски). */
        fun strokeStyle(): NoteInkView.StrokeStyle
        fun onStrokeStart(x: Float, y: Float)
        fun onStrokeEnd(stroke: InkStroke, style: NoteInkView.StrokeStyle, tap: Boolean)
        fun onErase(x: Float, y: Float, first: Boolean)
        fun onStatusTap(x: Float, y: Float)
        fun onStickerTap(index: Int)
        fun onDragStart()
        fun onDragEnd()
        fun onImageLongPress(index: Int)
        fun onCameraChanged()
        fun pendingStrokes(): List<InkStroke>
        /** Серое промежуточное слово над недописанной надписью: рамка [l, t, r, b] и текст. */
        fun interim(): Pair<FloatArray, String>?
        fun bitmap(img: BoardImage): Bitmap?
    }

    enum class Kind { STICKER, TEXT, IMAGE }
    class Hit(val kind: Kind, val index: Int)

    private enum class Mode { NONE, PAN, PINCH, INK, ERASE, DRAG, STATUS }

    private var mode = Mode.NONE
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastX = 0f
    private var lastY = 0f
    private var drag: Hit? = null
    private var dragLast = PointF()
    private var pinchA = -1
    private var pinchB = -1
    private var pinchD0 = 1f
    private var pinchZ0 = 1f
    private val pinchWorld = PointF()
    private var live: InkStroke? = null
    private var liveStyle: NoteInkView.StrokeStyle? = null
    private var clockOffset = 0L
    private var hover: PointF? = null
    private val fading = ArrayList<Pair<List<InkStroke>, Long>>()
    private val slop = Ui.dpf(context, 8f)

    private val painter = InkPainter()
    private val gridPaint = Paint().apply { color = Ui.LINE; strokeWidth = 0f; style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val hoverDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hoverRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(140, 133, 146, 168); strokeWidth = Ui.dpf(context, 1f)
    }
    private val rect = RectF()

    private val longPress = Runnable {
        if (mode == Mode.PAN && !moved) {
            val h = hitAt(wx(downX), wy(downY))
            if (h?.kind == Kind.IMAGE) {
                mode = Mode.NONE
                host.onImageLongPress(h.index)
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 24) {
            pointerIcon = android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL)
        }
    }

    // ---------------------------------------------------------------- камера

    private val b get() = host.board()
    fun wx(sx: Float) = sx / b.camZ + b.camX
    fun wy(sy: Float) = sy / b.camZ + b.camY

    /** Показать всё, что есть на доске. */
    fun fitAll() {
        val bb = contentBounds()
        if (bb == null || width == 0) {
            b.camZ = 1f
            b.camX = -Ui.dpf(context, 40f)
            b.camY = -Ui.dpf(context, 40f)
        } else {
            val pad = Ui.dpf(context, 40f)
            val z = min(width / (bb.width() + pad * 2), height / (bb.height() + pad * 2)).coerceIn(0.1f, 2f)
            b.camZ = z
            b.camX = bb.centerX() - width / 2f / z
            b.camY = bb.centerY() - height / 2f / z
        }
        host.onCameraChanged()
        invalidate()
    }

    fun contentBounds(): RectF? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
        fun add(x: Float, y: Float) { l = min(l, x); t = min(t, y); r = max(r, x); bt = max(bt, y) }
        for (d in b.strokes) { var i = 0; while (i + 1 < d.pts.size) { add(d.pts[i], d.pts[i + 1]); i += 3 } }
        for (x in b.texts) { add(x.x, x.y - x.size); add(x.x + textWidth(x), x.y + x.size * 0.3f) }
        for (m in b.images) { add(m.x, m.y); add(m.x + m.w, m.y + m.h) }
        for (s in b.stickers) { add(s.x, s.y); add(s.x + s.w, s.y + s.h) }
        return if (l <= r) RectF(l, t, r, bt) else null
    }

    fun fade(strokes: List<InkStroke>) {
        fading += strokes to SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    fun textWidth(t: BoardText): Float {
        textPaint.textSize = t.size
        return textPaint.measureText(t.text)
    }

    /** Что лежит в точке доски (сверху вниз: метки, тексты, картинки). */
    fun hitAt(x: Float, y: Float): Hit? {
        for (i in b.stickers.indices.reversed()) {
            val s = b.stickers[i]
            if (x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h) return Hit(Kind.STICKER, i)
        }
        for (i in b.texts.indices.reversed()) {
            val t = b.texts[i]
            if (x >= t.x && x <= t.x + textWidth(t) && y >= t.y - t.size && y <= t.y + t.size * 0.3f) return Hit(Kind.TEXT, i)
        }
        for (i in b.images.indices.reversed()) {
            val m = b.images[i]
            if (x >= m.x && x <= m.x + m.w && y >= m.y && y <= m.y + m.h) return Hit(Kind.IMAGE, i)
        }
        return null
    }

    // ---------------------------------------------------------------- касания

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    private fun penLike(tool: Int) = isPen(tool) || tool == MotionEvent.TOOL_TYPE_MOUSE || host.fingerWrites()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                val tool = ev.getToolType(idx)
                if (isPen(tool) && (mode == Mode.PAN || mode == Mode.PINCH || mode == Mode.NONE)) {
                    begin(ev, idx) // ладонь или пальцы лежали — работает перо
                } else if (!penLike(tool) && (mode == Mode.PAN || mode == Mode.DRAG)) {
                    if (mode == Mode.DRAG) host.onDragEnd()
                    startPinch(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> move(ev)
            MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (mode == Mode.PINCH && (id == pinchA || id == pinchB)) {
                    val other = if (id == pinchA) pinchB else pinchA
                    val oi = ev.findPointerIndex(other)
                    mode = if (oi >= 0) Mode.PAN else Mode.NONE
                    activePointer = other
                    if (oi >= 0) { lastX = ev.getX(oi); lastY = ev.getY(oi) }
                    moved = true
                } else if (id == activePointer) {
                    finish(ev, ev.actionIndex)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (ev.getPointerId(ev.actionIndex) == activePointer) finish(ev, ev.actionIndex)
                mode = Mode.NONE
                removeCallbacks(longPress)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAG) host.onDragEnd()
                live = null
                mode = Mode.NONE
                removeCallbacks(longPress)
                invalidate()
            }
        }
        return true
    }

    private fun begin(ev: MotionEvent, idx: Int) {
        removeCallbacks(longPress)
        val tool = ev.getToolType(idx)
        activePointer = ev.getPointerId(idx)
        downX = ev.getX(idx); downY = ev.getY(idx)
        lastX = downX; lastY = downY
        downTime = SystemClock.uptimeMillis()
        moved = false
        hover = null
        val x = wx(downX)
        val y = wy(downY)
        val t = host.tool()
        if (t == BoardActivity.Tool.HAND || !penLike(tool)) {
            val h = if (t == BoardActivity.Tool.HAND) hitAt(x, y) else null
            if (h != null) {
                mode = Mode.DRAG
                drag = h
                dragLast.set(x, y)
                host.onDragStart()
            } else {
                mode = Mode.PAN
            }
            if (!isPen(tool)) postDelayed(longPress, 550)
            return
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        requestUnbufferedDispatch(ev)
        if (tool == MotionEvent.TOOL_TYPE_ERASER || t == BoardActivity.Tool.ERASER) {
            mode = Mode.ERASE
            host.onErase(x, y, true)
            return
        }
        if (t == BoardActivity.Tool.STATUS) {
            mode = Mode.STATUS
            return
        }
        mode = Mode.INK
        host.onStrokeStart(x, y)
        clockOffset = System.currentTimeMillis() - SystemClock.uptimeMillis()
        val st = host.strokeStyle()
        liveStyle = NoteInkView.StrokeStyle(st.kind, st.color, st.width / b.camZ)
        live = InkStroke(tool).also { it.points.add(InkPoint(x, y, ev.eventTime + clockOffset, pressure(ev, idx, -1))) }
        invalidate()
    }

    private fun pressure(ev: MotionEvent, idx: Int, h: Int): Float {
        if (!isPen(ev.getToolType(idx))) return 0.5f
        val p = if (h < 0) ev.getPressure(idx) else ev.getHistoricalPressure(idx, h)
        return (p / 0.6f).coerceIn(0f, 1f)
    }

    private fun move(ev: MotionEvent) {
        if (mode == Mode.PINCH) { pinch(ev); return }
        val idx = ev.findPointerIndex(activePointer)
        if (idx < 0) return
        val sx = ev.getX(idx)
        val sy = ev.getY(idx)
        if (!moved && hypot(sx - downX, sy - downY) > slop) {
            moved = true
            removeCallbacks(longPress)
        }
        when (mode) {
            Mode.PAN -> {
                b.camX -= (sx - lastX) / b.camZ
                b.camY -= (sy - lastY) / b.camZ
                lastX = sx; lastY = sy
                host.onCameraChanged()
                invalidate()
            }
            Mode.DRAG -> {
                val x = wx(sx); val y = wy(sy)
                val dx = x - dragLast.x; val dy = y - dragLast.y
                dragLast.set(x, y)
                drag?.let { h ->
                    when (h.kind) {
                        Kind.STICKER -> b.stickers.getOrNull(h.index)?.let { it.x += dx; it.y += dy }
                        Kind.TEXT -> b.texts.getOrNull(h.index)?.let { it.x += dx; it.y += dy }
                        Kind.IMAGE -> b.images.getOrNull(h.index)?.let { it.x += dx; it.y += dy }
                    }
                }
                invalidate()
            }
            Mode.ERASE -> {
                for (h in 0 until ev.historySize) host.onErase(wx(ev.getHistoricalX(idx, h)), wy(ev.getHistoricalY(idx, h)), false)
                host.onErase(wx(sx), wy(sy), false)
            }
            Mode.INK -> {
                val s = live ?: return
                for (h in 0 until ev.historySize) {
                    add(s, wx(ev.getHistoricalX(idx, h)), wy(ev.getHistoricalY(idx, h)), ev.getHistoricalEventTime(h), pressure(ev, idx, h))
                }
                add(s, wx(sx), wy(sy), ev.eventTime, pressure(ev, idx, -1))
                invalidate()
            }
            else -> Unit
        }
    }

    private fun add(s: InkStroke, x: Float, y: Float, t: Long, p: Float) {
        val last = s.points[s.points.lastIndex]
        if (last.x == x && last.y == y) return
        s.points.add(InkPoint(x, y, t + clockOffset, p))
    }

    private fun finish(ev: MotionEvent, idx: Int) {
        removeCallbacks(longPress)
        val sx = ev.getX(idx)
        val sy = ev.getY(idx)
        val quick = SystemClock.uptimeMillis() - downTime < 350 && hypot(sx - downX, sy - downY) < slop
        when (mode) {
            Mode.INK -> {
                val s = live
                if (s != null) {
                    add(s, wx(sx), wy(sy), ev.eventTime, pressure(ev, idx, -1))
                    live = null
                    host.onStrokeEnd(s, liveStyle ?: host.strokeStyle(), quick)
                }
            }
            Mode.STATUS -> if (quick) host.onStatusTap(wx(sx), wy(sy))
            Mode.PAN -> if (quick && !moved) {
                val h = hitAt(wx(sx), wy(sy))
                if (h?.kind == Kind.STICKER) host.onStickerTap(h.index)
            }
            Mode.DRAG -> host.onDragEnd()
            else -> Unit
        }
        mode = Mode.NONE
        drag = null
        invalidate()
    }

    private fun startPinch(ev: MotionEvent) {
        removeCallbacks(longPress)
        var a = -1
        var c = -1
        for (i in 0 until ev.pointerCount) {
            if (isPen(ev.getToolType(i))) continue
            if (a < 0) a = i else if (c < 0) c = i
        }
        if (a < 0 || c < 0) return
        pinchA = ev.getPointerId(a)
        pinchB = ev.getPointerId(c)
        pinchD0 = max(1f, hypot(ev.getX(a) - ev.getX(c), ev.getY(a) - ev.getY(c)))
        pinchZ0 = b.camZ
        val mx = (ev.getX(a) + ev.getX(c)) / 2f
        val my = (ev.getY(a) + ev.getY(c)) / 2f
        pinchWorld.set(wx(mx), wy(my))
        mode = Mode.PINCH
        moved = true
        drag = null
    }

    private fun pinch(ev: MotionEvent) {
        val a = ev.findPointerIndex(pinchA)
        val c = ev.findPointerIndex(pinchB)
        if (a < 0 || c < 0) return
        val d = max(1f, hypot(ev.getX(a) - ev.getX(c), ev.getY(a) - ev.getY(c)))
        val z = (pinchZ0 * d / pinchD0).coerceIn(0.1f, 5f)
        val mx = (ev.getX(a) + ev.getX(c)) / 2f
        val my = (ev.getY(a) + ev.getY(c)) / 2f
        b.camZ = z
        b.camX = pinchWorld.x - mx / z
        b.camY = pinchWorld.y - my / z
        host.onCameraChanged()
        invalidate()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Колёсико мыши (на компьютере/эмуляторе): прокрутка, с Ctrl — масштаб.
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if ((event.metaState and android.view.KeyEvent.META_CTRL_ON) != 0) {
                val wxp = wx(event.x); val wyp = wy(event.y)
                b.camZ = (b.camZ * (1f + v * 0.1f)).coerceIn(0.1f, 5f)
                b.camX = wxp - event.x / b.camZ
                b.camY = wyp - event.y / b.camZ
            } else {
                b.camY -= v * Ui.dpf(context, 60f) / b.camZ
            }
            host.onCameraChanged()
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isPen(event.getToolType(0)) || host.tool() == BoardActivity.Tool.HAND) return super.onHoverEvent(event)
        hover = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else PointF(event.x, event.y)
        invalidate()
        return true
    }

    // ---------------------------------------------------------------- рисование

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ui.PAPER)
        val z = b.camZ
        canvas.save()
        canvas.scale(z, z)
        canvas.translate(-b.camX, -b.camY)
        drawGrid(canvas, z)
        for (m in b.images) drawImage(canvas, m, z)
        for (d in b.strokes) painter.drawing(canvas, d)
        for (t in b.texts) {
            textPaint.color = t.color
            textPaint.textSize = t.size
            canvas.drawText(t.text, t.x, t.y, textPaint)
        }
        for (s in b.stickers) drawSticker(canvas, s)
        val pendingStyle = NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(context, 2.6f) / z)
        for (s in host.pendingStrokes()) painter.stroke(canvas, s, pendingStyle, 255)
        live?.let { painter.stroke(canvas, it, liveStyle ?: pendingStyle, 255) }
        val now = SystemClock.uptimeMillis()
        fading.removeAll { now - it.second > NoteInkView.FADE_MS }
        for ((strokes, t0) in fading) {
            val a = (255 * (1f - (now - t0).toFloat() / NoteInkView.FADE_MS)).toInt().coerceIn(0, 255)
            for (s in strokes) painter.stroke(canvas, s, pendingStyle, a)
        }
        host.interim()?.let { (bb, text) ->
            val h = bb[3] - bb[1]
            textPaint.color = Ui.MUTED
            textPaint.textSize = max(h * 0.75f, Ui.dpf(context, 14f) / z)
            canvas.drawText(text, bb[0], bb[1] - Ui.dpf(context, 8f) / z, textPaint)
        }
        canvas.restore()
        hover?.let {
            if (host.tool() == BoardActivity.Tool.ERASER) {
                canvas.drawCircle(it.x, it.y, Ui.dpf(context, 14f), hoverRing)
            } else {
                hoverDot.color = host.strokeStyle().color
                hoverDot.alpha = 170
                canvas.drawCircle(it.x, it.y, Ui.dpf(context, 2.5f), hoverDot)
            }
        }
        if (fading.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawGrid(canvas: Canvas, z: Float) {
        var step = 40f
        while (step * z < Ui.dpf(context, 14f)) step *= 5f
        val l = b.camX; val t = b.camY
        val r = l + width / z; val bt = t + height / z
        var x = Math.floor((l / step).toDouble()).toFloat() * step
        while (x < r) { canvas.drawLine(x, t, x, bt, gridPaint); x += step }
        var y = Math.floor((t / step).toDouble()).toFloat() * step
        while (y < bt) { canvas.drawLine(l, y, r, y, gridPaint); y += step }
    }

    private fun drawImage(canvas: Canvas, m: BoardImage, z: Float) {
        val frame = Ui.dpf(context, 5f) / z
        rect.set(m.x - frame, m.y - frame, m.x + m.w + frame, m.y + m.h + frame)
        fillPaint.color = Color.WHITE
        canvas.drawRect(rect, fillPaint)
        linePaint.color = Ui.RULE
        linePaint.strokeWidth = 0f
        canvas.drawRect(rect, linePaint)
        val bmp = host.bitmap(m)
        rect.set(m.x, m.y, m.x + m.w, m.y + m.h)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, rect, bitmapPaint)
        } else {
            fillPaint.color = Ui.DESK
            canvas.drawRect(rect, fillPaint)
        }
    }

    private fun drawSticker(canvas: Canvas, s: BoardSticker) {
        val (label, fg, bg) = STATUS[s.status.coerceIn(0, 2)]
        rect.set(s.x, s.y, s.x + s.w, s.y + s.h)
        fillPaint.color = bg
        canvas.drawRoundRect(rect, s.h / 2f, s.h / 2f, fillPaint)
        linePaint.color = fg
        linePaint.strokeWidth = 1.5f * s.scale
        canvas.drawRoundRect(rect, s.h / 2f, s.h / 2f, linePaint)
        textPaint.color = fg
        textPaint.textSize = 16f * s.scale
        textPaint.isFakeBoldText = true
        val tw = textPaint.measureText(label)
        canvas.drawText(label, s.x + (s.w - tw) / 2f, s.y + s.h / 2f + textPaint.textSize * 0.36f, textPaint)
        textPaint.isFakeBoldText = false
    }

    companion object {
        /** Подпись, цвет текста, цвет фона — для статусов 0, 1, 2. */
        val STATUS = listOf(
            Triple("Не сдано", Ui.BAD, Ui.BAD_SOFT),
            Triple("В работе", Ui.WARN, Ui.WARN_SOFT),
            Triple("Сдано", Ui.OK, Ui.OK_SOFT),
        )
    }
}
