package party.shulya.freewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Блок записи: абзац текста, строка-задача или картинка. */
sealed class Block {
    class Para(var text: String) : Block()
    class Task(var text: String, var done: Boolean) : Block()
    class Image(val file: String, val width: Int, val height: Int) : Block()
}

/** Рисунок ручкой или маркером: точки в координатах листа (x, y, нажим). */
class Drawing(val kind: String, val color: Int, val width: Float, val pts: FloatArray)

/** Одна запись ежедневника. */
class Note(
    val id: String,
    var title: String,
    val created: Long,
    var updated: Long,
    val blocks: MutableList<Block>,
    val drawings: MutableList<Drawing>,
    var caret: Int,
) {
    /** Позиция курсора внутри строки caret; -1 — в конце строки. */
    var caretOffset: Int = -1

    val tasksDone: Int get() = blocks.count { it is Block.Task && it.done && it.text.isNotBlank() }
    val tasksOpen: Int get() = blocks.count { it is Block.Task && !it.done && it.text.isNotBlank() }

    /** Первая непустая строка текста — для списка в ежедневнике. */
    val preview: String
        get() = blocks.firstNotNullOfOrNull {
            when (it) {
                is Block.Para -> it.text.trim().ifEmpty { null }
                is Block.Task -> it.text.trim().ifEmpty { null }
                else -> null
            }
        }.orEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 1)
        o.put("id", id)
        o.put("title", title)
        o.put("created", created)
        o.put("updated", updated)
        o.put("caret", caret)
        o.put("caretOff", caretOffset)
        val bl = JSONArray()
        for (b in blocks) {
            val j = JSONObject()
            when (b) {
                is Block.Para -> { j.put("t", "p"); j.put("text", b.text) }
                is Block.Task -> { j.put("t", "task"); j.put("text", b.text); j.put("done", b.done) }
                is Block.Image -> { j.put("t", "img"); j.put("file", b.file); j.put("w", b.width); j.put("h", b.height) }
            }
            bl.put(j)
        }
        o.put("blocks", bl)
        val dr = JSONArray()
        for (d in drawings) {
            val j = JSONObject()
            j.put("kind", d.kind)
            j.put("color", d.color)
            j.put("w", d.width.toDouble())
            val pts = JSONArray()
            for (v in d.pts) pts.put((v * 10f).roundToInt() / 10.0)
            j.put("pts", pts)
            dr.put(j)
        }
        o.put("drawings", dr)
        return o
    }

    fun copy(): Note = fromJson(toJson())

    companion object {
        fun create(title: String): Note {
            val now = System.currentTimeMillis()
            return Note(UUID.randomUUID().toString(), title, now, now, mutableListOf(Block.Para("")), mutableListOf(), 0)
        }

        fun fromJson(o: JSONObject): Note {
            val blocks = ArrayList<Block>()
            val bl = o.optJSONArray("blocks") ?: JSONArray()
            for (i in 0 until bl.length()) {
                val j = bl.getJSONObject(i)
                when (j.optString("t")) {
                    "task" -> blocks += Block.Task(j.optString("text"), j.optBoolean("done"))
                    "img" -> blocks += Block.Image(j.optString("file"), j.optInt("w"), j.optInt("h"))
                    else -> blocks += Block.Para(j.optString("text"))
                }
            }
            if (blocks.isEmpty()) blocks += Block.Para("")
            val drawings = ArrayList<Drawing>()
            val dr = o.optJSONArray("drawings") ?: JSONArray()
            for (i in 0 until dr.length()) {
                val j = dr.getJSONObject(i)
                val pa = j.optJSONArray("pts") ?: JSONArray()
                val pts = FloatArray(pa.length()) { pa.optDouble(it).toFloat() }
                drawings += Drawing(j.optString("kind", "pen"), j.optInt("color"), j.optDouble("w", 3.0).toFloat(), pts)
            }
            return Note(
                id = o.getString("id"),
                title = o.optString("title", "Запись"),
                created = o.optLong("created"),
                updated = o.optLong("updated"),
                blocks = blocks,
                drawings = drawings,
                caret = o.optInt("caret", blocks.size - 1).coerceIn(0, blocks.size - 1),
            ).apply { caretOffset = o.optInt("caretOff", -1) }
        }
    }
}

/** Хранение записей: по файлу JSON на запись, картинки — отдельными файлами. */
class NoteStore(context: Context) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }
    val imageDir = File(context.filesDir, "note_images").apply { mkdirs() }

    fun list(): List<Note> = dir.listFiles { f -> f.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { f -> runCatching { Note.fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.created }

    fun load(id: String): Note? =
        runCatching { Note.fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    fun save(note: Note) {
        note.updated = System.currentTimeMillis()
        val tmp = File(dir, "${note.id}.json.tmp")
        tmp.writeText(note.toJson().toString())
        val target = File(dir, "${note.id}.json")
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(note: Note) {
        for (b in note.blocks) if (b is Block.Image) File(imageDir, b.file).delete()
        File(dir, "${note.id}.json").delete()
    }

    /**
     * Скопировать картинку из галереи к себе, уменьшив до 2000 px по длинной стороне.
     * Возвращает блок-картинку или null, если файл не читается.
     */
    fun importImage(context: Context, uri: Uri): Block.Image? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val name = UUID.randomUUID().toString() + ".jpg"
        File(imageDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val block = Block.Image(name, bmp.width, bmp.height)
        bmp.recycle()
        return block
    }

    /** Картинка для показа на листе: не шире maxWidth пикселей. */
    fun loadImage(block: Block.Image, maxWidth: Int): Bitmap? {
        val f = File(imageDir, block.file)
        if (!f.exists()) return null
        var sample = 1
        while (block.width / sample > maxWidth * 2) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
