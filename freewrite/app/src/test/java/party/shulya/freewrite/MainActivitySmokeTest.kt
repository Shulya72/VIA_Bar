package party.shulya.freewrite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * Проверка экрана на компьютере (Robolectric), без планшета.
 * Экран размером с MatePad Mini (2560×1600, плотность 2x): запуск, штрих пером, касание пальцем.
 * Скриншоты — в app/build/screenshots/ (смотреть глазами; распознавание здесь не работает —
 * модели ML Kit на компьютере нет).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivitySmokeTest {

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun portrait_penDrawsFingerDoesNot() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val canvas = find(activity.window.decorView, InkCanvasView::class.java)
        assertTrue("поле для письма должно иметь размер", canvas.width > 0 && canvas.height > 0)

        // Штрих пером — должен остаться.
        drawStroke(canvas, MotionEvent.TOOL_TYPE_STYLUS, 0.2f, 0.4f)
        assertEquals(1, canvas.strokeCount)
        assertEquals(1, canvas.stats.penDowns)

        // Касание пальцем — следа быть не должно.
        drawStroke(canvas, MotionEvent.TOOL_TYPE_FINGER, 0.6f, 0.6f)
        assertEquals(1, canvas.strokeCount)
        assertEquals(1, canvas.stats.touchesIgnored)

        // Ещё два штриха пером, чтобы на скриншоте было «слово», и пауза автораспознавания.
        drawStroke(canvas, MotionEvent.TOOL_TYPE_STYLUS, 0.35f, 0.45f)
        drawStroke(canvas, MotionEvent.TOOL_TYPE_STYLUS, 0.5f, 0.42f)
        assertEquals(3, canvas.strokeCount)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))

        screenshot(activity.window.decorView, "portrait.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun landscape_layoutSideBySide() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val canvas = find(activity.window.decorView, InkCanvasView::class.java)
        val loc = IntArray(2)
        canvas.getLocationInWindow(loc)
        assertTrue("в горизонтальном положении поле справа", loc[0] > activity.window.decorView.width / 4)
        drawStroke(canvas, MotionEvent.TOOL_TYPE_STYLUS, 0.3f, 0.5f)
        assertEquals(1, canvas.strokeCount)
        screenshot(activity.window.decorView, "landscape.png")
    }

    // ------------------------------------------------------------------ helpers

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Волнистый штрих, похожий на рукописную букву, от точки (fx, fy) в долях поля. */
    private fun drawStroke(view: View, tool: Int, fx: Float, fy: Float) {
        val down = SystemClock.uptimeMillis()
        val x0 = view.width * fx
        val y0 = view.height * fy
        val w = view.width * 0.12f
        val h = view.height * 0.08f
        val n = 24
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, x0, y0, tool))
        for (i in 1..n) {
            val t = i / n.toFloat()
            val x = x0 + w * t
            val y = y0 + h * kotlin.math.sin(t * Math.PI * 3).toFloat()
            view.dispatchTouchEvent(event(down, down + i * 8L, MotionEvent.ACTION_MOVE, x, y, tool))
        }
        view.dispatchTouchEvent(event(down, down + (n + 1) * 8L, MotionEvent.ACTION_UP, x0 + w, y0, tool))
        idle()
    }

    private fun event(down: Long, time: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 0.5f
                size = 0.1f
            },
        )
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun <T : View> find(root: View, type: Class<T>): T {
        if (type.isInstance(root)) return type.cast(root)!!
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                try {
                    return find(root.getChildAt(i), type)
                } catch (_: NoSuchElementException) {
                }
            }
        }
        throw NoSuchElementException(type.simpleName)
    }

    private fun screenshot(view: View, name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
