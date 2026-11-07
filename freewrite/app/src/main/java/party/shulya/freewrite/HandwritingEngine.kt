package party.shulya.freewrite

import android.content.Context
import android.os.SystemClock
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
// В ML Kit 19.0.0 классы распознавания переехали в пакет ...digitalink.recognition.
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import java.util.Locale

class Candidate(val text: String, val score: Float?)

class RecognitionOutcome(
    val requestId: Int,
    val candidates: List<Candidate>,
    val millis: Long,
    val strokeCount: Int,
    val pointCount: Int,
    val error: String?,
)

/**
 * Обёртка над Google ML Kit Digital Ink Recognition.
 *
 * Все ответы ML Kit приходят в главный поток. Каждый запрос распознавания получает номер;
 * показывать нужно только ответ на последний запрос (номер = latestRequestId), а более старые
 * ответы — отбрасывать. Это основа будущей защиты от дублирования текста.
 */
class HandwritingEngine(private val log: (String) -> Unit) {

    enum class State { UNKNOWN, CHECKING, MISSING, DOWNLOADING, DELETING, READY, ERROR }

    class Language(val tag: String, val title: String) {
        var model: DigitalInkRecognitionModel? = null
        var state = State.UNKNOWN
        var error: String? = null
        var downloadStartedAt = 0L
        var downloadSeconds: Double? = null
        var recognizer: DigitalInkRecognizer? = null
        var firstRecognitionMs: Long? = null
        var recognitions = 0
        var totalMs = 0L
    }

    val languages = listOf(Language("ru", "Русский"), Language("en-US", "English"))

    var onChanged: (() -> Unit)? = null
    var initError: String? = null
        private set

    private var manager: RemoteModelManager? = null
    private var nextRequestId = 0
    var latestRequestId = 0
        private set

    fun language(tag: String): Language = languages.first { it.tag == tag }

    fun start(context: Context) {
        try {
            manager = RemoteModelManager.getInstance()
        } catch (first: Throwable) {
            // Обычно ML Kit запускается сам при старте приложения (MlKitInitProvider).
            // Если система этого не сделала — запускаем вручную официальной функцией.
            log("ML Kit не запустился сам (${describe(first)}), запускаю вручную")
            try {
                MlKit.initialize(context.applicationContext)
                manager = RemoteModelManager.getInstance()
                log("ML Kit запущен вручную")
            } catch (t: Throwable) {
                initError = describe(t)
                log("ML Kit не запустился: $initError")
            }
        }
        for (lang in languages) {
            try {
                val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang.tag)
                if (id == null) {
                    lang.state = State.ERROR
                    lang.error = "ML Kit не знает язык «${lang.tag}»"
                } else {
                    lang.model = DigitalInkRecognitionModel.builder(id).build()
                }
            } catch (t: Throwable) {
                lang.state = State.ERROR
                lang.error = describe(t)
            }
            if (initError != null) {
                lang.state = State.ERROR
                lang.error = initError
            }
            if (lang.state == State.ERROR) log("${lang.title}: ${lang.error}")
            refresh(lang)
        }
        changed()
    }

    /** Проверить, скачана ли модель языка. */
    fun refresh(lang: Language) {
        val m = manager ?: return
        val model = lang.model ?: return
        lang.state = State.CHECKING
        changed()
        try {
            m.isModelDownloaded(model)
                .addOnSuccessListener { ok ->
                    lang.state = if (ok == true) State.READY else State.MISSING
                    lang.error = null
                    log("${lang.title}: модель ${if (ok == true) "уже на устройстве" else "не скачана"}")
                    changed()
                }
                .addOnFailureListener { e -> fail(lang, "проверка модели", e) }
        } catch (t: Throwable) {
            fail(lang, "проверка модели", t)
        }
    }

    fun download(lang: Language) {
        val m = manager
        val model = lang.model
        if (m == null || model == null) {
            log("${lang.title}: скачать нельзя — ${lang.error ?: initError ?: "ML Kit не готов"}")
            return
        }
        if (lang.state == State.DOWNLOADING) return
        lang.state = State.DOWNLOADING
        lang.error = null
        lang.downloadStartedAt = SystemClock.elapsedRealtime()
        log("${lang.title}: начинаю загрузку модели")
        changed()
        try {
            m.download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    val sec = (SystemClock.elapsedRealtime() - lang.downloadStartedAt) / 1000.0
                    lang.downloadSeconds = sec
                    lang.state = State.READY
                    log("${lang.title}: модель скачана за ${String.format(Locale.US, "%.1f", sec)} с")
                    changed()
                }
                .addOnFailureListener { e -> fail(lang, "загрузка модели", e) }
        } catch (t: Throwable) {
            fail(lang, "загрузка модели", t)
        }
    }

    fun delete(lang: Language) {
        val m = manager ?: return
        val model = lang.model ?: return
        closeRecognizer(lang)
        lang.state = State.DELETING
        changed()
        try {
            m.deleteDownloadedModel(model)
                .addOnSuccessListener {
                    log("${lang.title}: модель удалена с устройства")
                    lang.downloadSeconds = null
                    refresh(lang)
                }
                .addOnFailureListener { e -> fail(lang, "удаление модели", e) }
        } catch (t: Throwable) {
            fail(lang, "удаление модели", t)
        }
    }

    /** Сделать так, чтобы ответы на уже отправленные запросы не показывались. */
    fun invalidatePending() {
        latestRequestId = ++nextRequestId
    }

    /**
     * Распознать штрихи. preContext — до 20 последних символов уже набранного текста:
     * ML Kit учитывает их, чтобы лучше угадать слово и решить, нужен ли пробел
     * (тогда вариант начинается с пробела).
     *
     * areaHeight — по рекомендации Google высота ОДНОЙ строки текста, а не всего поля:
     * распознаватель считает, что в области одна строка, и по размеру букв относительно
     * неё отличает «о» от «О» и запятую от косой черты.
     */
    fun recognize(
        lang: Language,
        strokes: List<InkStroke>,
        preContext: String,
        areaWidth: Float,
        areaHeight: Float,
        done: (RecognitionOutcome) -> Unit,
    ): Int {
        val id = ++nextRequestId
        latestRequestId = id
        val points = strokes.sumOf { it.points.size }
        val model = lang.model
        if (model == null || lang.state != State.READY) {
            val why = when (lang.state) {
                State.DOWNLOADING -> "Модель «${lang.title}» ещё скачивается — подождите"
                State.ERROR -> "Модель «${lang.title}» недоступна: ${lang.error}"
                else -> "Модель «${lang.title}» не скачана — нажмите «Скачать» в разделе 1"
            }
            done(RecognitionOutcome(id, emptyList(), 0, strokes.size, points, why))
            return id
        }
        try {
            val inkBuilder = Ink.builder()
            for (s in strokes) {
                val sb = Ink.Stroke.builder()
                for (p in s.points) sb.addPoint(Ink.Point.create(p.x, p.y, p.t))
                inkBuilder.addStroke(sb.build())
            }
            val ink = inkBuilder.build()
            val recognizer = lang.recognizer
                ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                    .also { lang.recognizer = it }
            val context = RecognitionContext.builder()
                .setPreContext(preContext)
                .setWritingArea(WritingArea(areaWidth, areaHeight))
                .build()
            val t0 = SystemClock.elapsedRealtime()
            recognizer.recognize(ink, context)
                .addOnSuccessListener { result ->
                    val ms = SystemClock.elapsedRealtime() - t0
                    if (lang.firstRecognitionMs == null) lang.firstRecognitionMs = ms
                    lang.recognitions++
                    lang.totalMs += ms
                    val candidates = result.candidates.map { Candidate(it.text, it.score) }
                    done(RecognitionOutcome(id, candidates, ms, strokes.size, points, null))
                }
                .addOnFailureListener { e ->
                    val msg = describe(e)
                    log("${lang.title}: ошибка распознавания — $msg")
                    done(RecognitionOutcome(id, emptyList(), 0, strokes.size, points, "Ошибка распознавания: $msg"))
                }
        } catch (t: Throwable) {
            val msg = describe(t)
            log("${lang.title}: ошибка распознавания — $msg")
            done(RecognitionOutcome(id, emptyList(), 0, strokes.size, points, "Ошибка распознавания: $msg"))
        }
        return id
    }

    fun stateText(lang: Language): String = when (lang.state) {
        State.UNKNOWN, State.CHECKING -> "проверяю…"
        State.MISSING -> "не скачана"
        State.DOWNLOADING -> {
            val sec = (SystemClock.elapsedRealtime() - lang.downloadStartedAt) / 1000
            "скачивается… $sec с" + if (sec > 120) " (долго — проверьте интернет)" else ""
        }
        State.DELETING -> "удаляю…"
        State.READY -> "✅ готова" + (lang.downloadSeconds?.let { " (скачана за ${it.toInt()} с)" } ?: "")
        State.ERROR -> "❌ ошибка: ${lang.error}"
    }

    fun reportLines(): List<String> = languages.map { lang ->
        val sb = StringBuilder()
        sb.append(lang.title).append(" (").append(lang.tag).append("): ").append(stateText(lang))
        lang.firstRecognitionMs?.let { sb.append("; первое распознавание ").append(it).append(" мс") }
        if (lang.recognitions > 0) {
            sb.append("; распознаваний ").append(lang.recognitions)
                .append(", в среднем ").append(lang.totalMs / lang.recognitions).append(" мс")
        }
        sb.toString()
    }

    fun close() {
        for (lang in languages) closeRecognizer(lang)
    }

    private fun closeRecognizer(lang: Language) {
        try {
            lang.recognizer?.close()
        } catch (_: Throwable) {
        }
        lang.recognizer = null
        lang.firstRecognitionMs = null
    }

    private fun fail(lang: Language, what: String, e: Throwable) {
        lang.state = State.ERROR
        lang.error = describe(e)
        log("${lang.title}: $what — ошибка: ${lang.error}")
        changed()
    }

    private fun changed() {
        onChanged?.invoke()
    }

    companion object {
        /** Понятное описание ошибки с кодом ML Kit и цепочкой причин — для отчёта. */
        fun describe(t: Throwable): String {
            val sb = StringBuilder()
            var e: Throwable? = t
            var depth = 0
            while (e != null && depth < 4) {
                if (depth > 0) sb.append(" ← ")
                sb.append(e.javaClass.simpleName)
                if (e is MlKitException) sb.append(" [код ").append(e.errorCode).append("]")
                e.message?.let { sb.append(": ").append(it) }
                e = e.cause
                depth++
            }
            return sb.toString()
        }
    }
}
