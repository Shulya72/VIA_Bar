package party.shulya.freewrite

import android.view.MotionEvent
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Что система на самом деле сообщает о пере: тип инструмента, нажим, наклон,
 * наведение, частоту точек. Это ответ на вопрос 7 исследования — какие данные
 * M-Pencil Pro доступны обычному Android-приложению на HarmonyOS.
 */
class StylusStats {

    private val downs = IntArray(8)
    var hoverEvents = 0
    var touchesIgnored = 0
    var palmSwaps = 0
    var canceledStrokes = 0
    var strokes = 0
    var penSamples = 0
        private set

    private var minPressure = Float.MAX_VALUE
    private var maxPressure = 0f
    private val pressureLevels = HashSet<Int>()
    private var tiltReported = false
    private var maxTiltDeg = 0f
    private var orientationReported = false
    private var buttonStateSeen = 0

    var lastTool = -1
        private set
    private var lastPressure = 0f
    private var lastTiltDeg = 0f
    private var lastRateHz = 0f
    private var maxRateHz = 0f

    fun downs(tool: Int): Int = if (tool in downs.indices) downs[tool] else 0

    val penDowns: Int
        get() = downs(MotionEvent.TOOL_TYPE_STYLUS) + downs(MotionEvent.TOOL_TYPE_ERASER)

    fun onDown(tool: Int) {
        if (tool in downs.indices) downs[tool]++ else downs[MotionEvent.TOOL_TYPE_UNKNOWN]++
        lastTool = tool
    }

    fun onSample(event: MotionEvent, pi: Int) {
        val tool = event.getToolType(pi)
        lastTool = tool
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
        penSamples += event.historySize + 1
        val p = event.getPressure(pi)
        lastPressure = p
        minPressure = min(minPressure, p)
        maxPressure = max(maxPressure, p)
        if (pressureLevels.size < 5000) pressureLevels.add((p * 1000f).toInt())
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, pi)
        lastTiltDeg = Math.toDegrees(tilt.toDouble()).toFloat()
        if (tilt != 0f) tiltReported = true
        maxTiltDeg = max(maxTiltDeg, lastTiltDeg)
        if (event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pi) != 0f) orientationReported = true
        buttonStateSeen = buttonStateSeen or event.buttonState
    }

    fun onStrokeDone(stroke: InkStroke) {
        strokes++
        val pts = stroke.points
        if (pts.size >= 5) {
            val dt = pts[pts.lastIndex].t - pts[0].t
            if (dt >= 80) {
                lastRateHz = (pts.size - 1) * 1000f / dt
                maxRateHz = max(maxRateHz, lastRateHz)
            }
        }
    }

    private fun pressureVerdict(): String =
        if (pressureLevels.size <= 2 || maxPressure - minPressure < 0.02f) {
            "постоянный (${fmt2(maxPressure)})"
        } else {
            "меняется (${fmt2(minPressure)}–${fmt2(maxPressure)}, уровней: ${pressureLevels.size})"
        }

    /** Короткая сводка для экрана. */
    fun summary(): String {
        if (lastTool < 0) return "Коснитесь поля для письма пером — здесь появятся данные о пере."
        val sb = StringBuilder()
        sb.append("Последнее касание: ").append(toolName(lastTool))
        if (penSamples > 0 && (lastTool == MotionEvent.TOOL_TYPE_STYLUS || lastTool == MotionEvent.TOOL_TYPE_ERASER)) {
            sb.append(" · нажим ").append(fmt2(lastPressure))
            if (tiltReported) sb.append(" · наклон ").append(lastTiltDeg.roundToInt()).append("°")
        }
        sb.append('\n')
        sb.append("Касаний: перо ").append(penDowns)
            .append(" · палец ").append(downs(MotionEvent.TOOL_TYPE_FINGER))
        val other = downs(MotionEvent.TOOL_TYPE_MOUSE) + downs(MotionEvent.TOOL_TYPE_UNKNOWN)
        if (other > 0) sb.append(" · другое ").append(other)
        if (touchesIgnored > 0) sb.append(" · не пишут: ").append(touchesIgnored)
        sb.append('\n')
        sb.append("Точек в секунду: ")
        if (lastRateHz > 0) {
            sb.append(lastRateHz.roundToInt()).append(" (макс. ").append(maxRateHz.roundToInt()).append(")")
        } else {
            sb.append("—")
        }
        sb.append(" · наведение пера: ").append(if (hoverEvents > 0) "да" else "нет")
        if (penSamples > 0) {
            sb.append('\n')
            sb.append("Нажим: ").append(pressureVerdict())
            sb.append(" · наклон: ").append(if (tiltReported) "передаётся" else "не передаётся")
        }
        if (penDowns == 0 && downs(MotionEvent.TOOL_TYPE_FINGER) > 0) {
            sb.append("\n⚠ Система пока сообщает только «палец». Если вы касались пером — ")
                .append("включите «Писать и пальцем» и напишите нам об этом.")
        }
        return sb.toString()
    }

    /** Подробности для отчёта разработчику. */
    fun report(): String {
        val sb = StringBuilder()
        sb.append("Касания по типам: перо=").append(downs(MotionEvent.TOOL_TYPE_STYLUS))
            .append(", ластик=").append(downs(MotionEvent.TOOL_TYPE_ERASER))
            .append(", палец=").append(downs(MotionEvent.TOOL_TYPE_FINGER))
            .append(", мышь=").append(downs(MotionEvent.TOOL_TYPE_MOUSE))
            .append(", неизвестно=").append(downs(MotionEvent.TOOL_TYPE_UNKNOWN)).append('\n')
        sb.append("Штрихов: ").append(strokes)
            .append(", касаний без следа: ").append(touchesIgnored)
            .append(", ладонь→перо: ").append(palmSwaps)
            .append(", отменено системой: ").append(canceledStrokes).append('\n')
        sb.append("Точек пера: ").append(penSamples)
            .append(", точек в секунду: последний штрих ").append(lastRateHz.roundToInt())
            .append(", максимум ").append(maxRateHz.roundToInt()).append('\n')
        sb.append("Нажим: ").append(if (penSamples > 0) pressureVerdict() else "нет данных").append('\n')
        sb.append("Наклон: ")
            .append(if (tiltReported) "передаётся, максимум ${maxTiltDeg.roundToInt()}°" else "не передаётся (всегда 0)")
            .append('\n')
        sb.append("Поворот пера (orientation): ").append(if (orientationReported) "передаётся" else "не передаётся").append('\n')
        sb.append("Наведение пера (hover): ").append(hoverEvents).append(" событий").append('\n')
        sb.append("Кнопки пера (buttonState): ")
            .append(if (buttonStateSeen != 0) "0x" + Integer.toHexString(buttonStateSeen) else "не замечены")
        return sb.toString()
    }

    private fun fmt2(v: Float) = String.format(Locale.US, "%.2f", v)

    companion object {
        fun toolName(tool: Int): String = when (tool) {
            MotionEvent.TOOL_TYPE_STYLUS -> "перо"
            MotionEvent.TOOL_TYPE_ERASER -> "перо (как ластик)"
            MotionEvent.TOOL_TYPE_FINGER -> "палец"
            MotionEvent.TOOL_TYPE_MOUSE -> "мышь"
            MotionEvent.TOOL_TYPE_UNKNOWN -> "неизвестно"
            else -> "тип $tool"
        }
    }
}
