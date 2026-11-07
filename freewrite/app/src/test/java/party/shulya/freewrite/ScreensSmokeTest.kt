package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.robolectric.RuntimeEnvironment
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
 * Проверка экранов на компьютере (Robolectric), без планшета.
 * Размер — как у MatePad Mini (2560×1600, плотность 2x). Скриншоты — в app/build/screenshots/.
 *
 * ML Kit здесь не работает, и это ожидаемо: Robolectric исполняет код на обычной Java, а ML Kit
 * собран под Android (VerifyError, библиотека libdigitalink.so — только для Android).
 * Поэтому надпись в режиме «В текст» здесь не распознаётся и остаётся рисунком — это тоже проверяем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensSmokeTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun sampleNote(): Note {
        val n = Note.create("Планёрка · секция 3")
        n.blocks.clear()
        n.blocks += Block.Para("Прораб, геодезия, подрядчик «СтройМонтаж».")
        n.blocks += Block.Task("Исполнительная съёмка фундамента, секция 3", true)
        n.blocks += Block.Task("Акты АОСР на армирование колонн 2 этажа", false)
        n.blocks += Block.Para("Следующая планёрка — понедельник, 08:30.")
        n.caret = n.blocks.size - 1
        NoteStore(ctx).save(n)
        return n
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun home_listsNotes() {
        sampleNote()
        NoteStore(ctx).save(Note.create("Звонок с заказчиком"))
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        screenshot(activity.window.decorView, "home.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun note_penDrawsFingerTicksWriteFallsBack() {
        val n = sampleNote()
        val controller = Robolectric.buildActivity(
            NoteActivity::class.java,
            Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id),
        ).setup()
        val activity = controller.get()
        idle()
        val root = activity.window.decorView
        val ink = find(root, NoteInkView::class.java)
        assertTrue("слой пера должен иметь размер", ink.width > 0 && ink.height > 0)

        // 1. Ручка: штрих пером остаётся рисунком.
        root.findViewWithTag<View>("tool_pen").performClick()
        drawStroke(ink, MotionEvent.TOOL_TYPE_STYLUS, 0.55f, 0.62f)

        // 2. Палец по галочке второй задачи: «не сдано» → «сдано».
        val check = findAll(root) { it.contentDescription == "Не сдано" }.first()
        val c = IntArray(2); val o = IntArray(2)
        check.getLocationInWindow(c); ink.getLocationInWindow(o)
        tap(ink, MotionEvent.TOOL_TYPE_FINGER, (c[0] - o[0] + check.width / 2).toFloat(), (c[1] - o[1] + check.height / 2).toFloat())

        screenshot(root, "note-portrait.png")

        // 3. «В текст» без модели: надпись не теряется, а становится рисунком.
        root.findViewWithTag<View>("tool_write").performClick()
        drawStroke(ink, MotionEvent.TOOL_TYPE_STYLUS, 0.25f, 0.75f)
        drawStroke(ink, MotionEvent.TOOL_TYPE_STYLUS, 0.32f, 0.75f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))

        controller.pause()
        val saved = NoteStore(ctx).load(n.id)!!
        assertEquals("1 рисунок ручкой + 2 нераспознанных штриха", 3, saved.drawings.size)
        assertEquals("обе задачи сданы после касания пальцем", 2, saved.tasksDone)
        controller.resume()
        idle()

        // Нижняя часть листа после прокрутки.
        find(root, ScrollView::class.java).scrollTo(0, 400)
        idle()
        screenshot(root, "note-scrolled.png")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun note_landscape() {
        val n = sampleNote()
        val activity = Robolectric.buildActivity(
            NoteActivity::class.java,
            Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id),
        ).setup().get()
        idle()
        screenshot(activity.window.decorView, "note-landscape.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-xhdpi")
    fun diagnostics_penDrawsFingerDoesNot() {
        val activity = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().get()
        idle()
        val canvas = find(activity.window.decorView, InkCanvasView::class.java)
        drawStroke(canvas, MotionEvent.TOOL_TYPE_STYLUS, 0.2f, 0.4f)
        assertEquals(1, canvas.strokeCount)
        drawStroke(canvas, MotionEvent.TOOL_TYPE_FINGER, 0.6f, 0.6f)
        assertEquals(1, canvas.strokeCount)
        screenshot(activity.window.decorView, "diagnostics.png")
    }

    // ------------------------------------------------------------------ helpers

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Волнистый штрих от точки (fx, fy) в долях вида. */
    private fun drawStroke(view: View, tool: Int, fx: Float, fy: Float) {
        val down = SystemClock.uptimeMillis()
        val x0 = view.width * fx
        val y0 = view.height * fy
        val w = view.width * 0.08f
        val h = view.height * 0.03f
        val n = 20
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, x0, y0, tool))
        for (i in 1..n) {
            val t = i / n.toFloat()
            view.dispatchTouchEvent(
                event(down, down + i * 8L, MotionEvent.ACTION_MOVE, x0 + w * t, y0 + h * kotlin.math.sin(t * Math.PI * 3).toFloat(), tool),
            )
        }
        view.dispatchTouchEvent(event(down, down + (n + 1) * 8L, MotionEvent.ACTION_UP, x0 + w, y0, tool))
        idle()
    }

    private fun tap(view: View, tool: Int, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, x, y, tool))
        view.dispatchTouchEvent(event(down, down + 60, MotionEvent.ACTION_UP, x, y, tool))
        idle()
    }

    private fun event(down: Long, time: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.4f; size = 0.1f })
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun <T : View> find(root: View, type: Class<T>): T =
        type.cast(findAll(root) { type.isInstance(it) }.first())!!

    private fun findAll(root: View, pred: (View) -> Boolean): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (pred(v)) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun screenshot(view: View, name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
