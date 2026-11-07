package party.shulya.freewrite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Текст на доске: x — левый край, y — линия букв (baseline), size — размер шрифта в единицах доски. */
class BoardText(var x: Float, var y: Float, var size: Float, var text: String, var color: Int)

/** Картинка на доске (файл — в общей папке картинок). */
class BoardImage(val file: String, var x: Float, var y: Float, var w: Float, var h: Float, val pxW: Int, val pxH: Int)

/** Метка статуса: 0 — не сдано, 1 — в работе, 2 — сдано. scale — размер (метка ставится «в рост» текущего масштаба). */
class BoardSticker(var x: Float, var y: Float, var status: Int, var scale: Float) {
    val w: Float get() = 128f * scale
    val h: Float get() = 36f * scale
}

/** Доска: бесконечное поле. Координаты — «единицы доски»; камера — что видно на экране. */
class Board(
    val id: String,
    var title: String,
    val created: Long,
    var updated: Long,
) {
    val strokes = ArrayList<Drawing>()
    val texts = ArrayList<BoardText>()
    val images = ArrayList<BoardImage>()
    val stickers = ArrayList<BoardSticker>()
    var camX = 0f
    var camY = 0f
    var camZ = 1f

    fun count(status: Int) = stickers.count { it.status == status }
    val isEmpty: Boolean get() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty() && stickers.isEmpty()

    fun toJson(withCamera: Boolean = true): JSONObject {
        val o = JSONObject()
        o.put("v", 1); o.put("kind", "board")
        o.put("id", id); o.put("title", title); o.put("created", created); o.put("updated", updated)
        if (withCamera) { o.put("camX", camX.toDouble()); o.put("camY", camY.toDouble()); o.put("camZ", camZ.toDouble()) }
        o.put("strokes", JSONArray().apply {
            for (d in strokes) put(JSONObject().apply {
                put("kind", d.kind); put("color", d.color); put("w", d.width.toDouble())
                put("pts", JSONArray().apply { for (v in d.pts) put((v * 10f).roundToInt() / 10.0) })
            })
        })
        o.put("texts", JSONArray().apply {
            for (t in texts) put(JSONObject().apply {
                put("x", t.x.toDouble()); put("y", t.y.toDouble()); put("size", t.size.toDouble()); put("text", t.text); put("color", t.color)
            })
        })
        o.put("images", JSONArray().apply {
            for (m in images) put(JSONObject().apply {
                put("file", m.file); put("x", m.x.toDouble()); put("y", m.y.toDouble()); put("w", m.w.toDouble()); put("h", m.h.toDouble())
                put("pxW", m.pxW); put("pxH", m.pxH)
            })
        })
        o.put("stickers", JSONArray().apply {
            for (s in stickers) put(JSONObject().apply {
                put("x", s.x.toDouble()); put("y", s.y.toDouble()); put("status", s.status); put("scale", s.scale.toDouble())
            })
        })
        return o
    }

    companion object {
        fun create(title: String): Board {
            val now = System.currentTimeMillis()
            return Board(UUID.randomUUID().toString(), title, now, now)
        }

        fun fromJson(o: JSONObject): Board {
            val b = Board(o.getString("id"), o.optString("title", "Доска"), o.optLong("created"), o.optLong("updated"))
            b.camX = o.optDouble("camX", 0.0).toFloat()
            b.camY = o.optDouble("camY", 0.0).toFloat()
            b.camZ = o.optDouble("camZ", 1.0).toFloat().coerceIn(0.1f, 5f)
            o.optJSONArray("strokes")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    val pa = j.optJSONArray("pts") ?: JSONArray()
                    b.strokes += Drawing(j.optString("kind", "pen"), j.optInt("color"), j.optDouble("w", 3.0).toFloat(),
                        FloatArray(pa.length()) { pa.optDouble(it).toFloat() })
                }
            }
            o.optJSONArray("texts")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    b.texts += BoardText(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), j.optDouble("size", 24.0).toFloat(),
                        j.optString("text"), j.optInt("color", Ui.INK))
                }
            }
            o.optJSONArray("images")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    b.images += BoardImage(j.optString("file"), j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
                        j.optDouble("w").toFloat(), j.optDouble("h").toFloat(), j.optInt("pxW"), j.optInt("pxH"))
                }
            }
            o.optJSONArray("stickers")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    b.stickers += BoardSticker(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), j.optInt("status").coerceIn(0, 2),
                        j.optDouble("scale", 1.0).toFloat())
                }
            }
            return b
        }
    }
}

/** Хранение досок: по файлу JSON на доску; картинки — в общей папке с картинками записей. */
class BoardStore(context: Context) {
    private val dir = File(context.filesDir, "boards").apply { mkdirs() }
    private val imageDir = File(context.filesDir, "note_images").apply { mkdirs() }

    fun list(): List<Board> = dir.listFiles { f -> f.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { f -> runCatching { Board.fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.created }

    fun load(id: String): Board? =
        runCatching { Board.fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    fun save(board: Board) {
        board.updated = System.currentTimeMillis()
        val tmp = File(dir, "${board.id}.json.tmp")
        tmp.writeText(board.toJson().toString())
        val target = File(dir, "${board.id}.json")
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(board: Board) {
        for (m in board.images) File(imageDir, m.file).delete()
        File(dir, "${board.id}.json").delete()
    }
}
