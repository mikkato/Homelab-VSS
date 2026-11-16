package ru.mytolm.frgate.util

import android.app.Application
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диагностика «немых» крашей в release.
 *
 * Почему Log.e: в app/proguard-rules.pro стоит -assumenosideeffects для Log.v/d/i —
 * R8 вырезает их целиком; Log.w/Log.e остаются. Все DIAG-записи идут ERROR-уровнем
 * с тегом TAG, поэтому выживают в release и отсеиваются одной маской в logcat.
 *
 * Что даёт:
 *  - ring buffer последних MAX_RECORDS записей в памяти (dump() — для crash-дампа);
 *  - зеркало всех записей в filesDir/diag/diag-<session>.log (переживает native-крах);
 *  - Thread.setDefaultUncaughtExceptionHandler: стек + dump буфера в
 *    filesDir/crash/diag-crash-<ts>.log, затем делегация прежнему дефолтному хендлеру;
 *  - session-маркер RUNNING/SHUTDOWN/CRASHED — прошлый запуск без SHUTDOWN = unclean exit.
 *
 * Секреты не логируем: url() режет query-строку (токены живут в query/заголовках).
 */
object Diag {
    const val TAG = "DIAG-FRIGATE"
    private const val MAX_RECORDS = 500

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val lock = Any()
    private val ring = ArrayDeque<String>()

    private var logFile: File? = null
    private var traceFile: File? = null
    private var crashDir: File? = null
    private var sessionFile: File? = null
    private var sessionId: String = "?"
    private var initDone = false

    @Volatile
    private var prevRunInfo: String? = null

    /** Идемпотентно; вызывать первым в Application.onCreate. */
    fun init(app: Application) {
        synchronized(lock) {
            if (initDone) return
            initDone = true
            val diagDir = File(app.filesDir, "diag").apply { mkdirs() }
            val sid = stampFmt.format(Date()) + "-" + Process.myPid()
            val sf = File(diagDir, "session")
            sessionId = sid
            logFile = File(diagDir, "diag-$sid.log")
            traceFile = File(diagDir, "diag-trace.log") // сквозной native-safe журнал (переживает сессии)
            val cd = File(app.filesDir, "crash").apply { mkdirs() }
            crashDir = cd
            sessionFile = sf
            prevRunInfo = try { sf.readText().trim() } catch (_: Exception) { null }
            installCrashHandler(cd, sf, sid)
            try { sf.writeText("$sid|RUNNING|${Date()}") } catch (_: Exception) {}
        }
        val prev = prevRunInfo
        val unclean = prev != null && !prev.endsWith("|SHUTDOWN")
        log(
            "process.start",
            "pid=${Process.myPid()} session=$sessionId build=${android.os.Build.MODEL} " +
                "android=${android.os.Build.VERSION.SDK_INT} prev=${prev ?: "none"} " +
                "prevRunUnclean=$unclean pendingCrashFiles=${pendingCrashFiles().size}"
        )
    }

    /** Прошлый запуск завершился без clean-shutdown-маркера (краш/kill/OOM-смерть процесса). */
    fun previousRunUnclean(): Boolean {
        val prev = prevRunInfo ?: return false
        return !prev.endsWith("|SHUTDOWN")
    }

    /** Ставится при штатном завершении UI (Activity.isFinishing) — маркер «живы до конца». */
    fun markCleanShutdown() {
        val f = synchronized(lock) { sessionFile } ?: return
        log("process.shutdown.clean")
        try { f.writeText("$sessionId|SHUTDOWN|${Date()}") } catch (_: Exception) {}
    }

    fun log(point: String, detail: String = "") {
        val rec = "${fmt.format(Date())} $point" + if (detail.isNotEmpty()) " | $detail" else ""
        val file = synchronized(lock) {
            ring.addLast(rec)
            while (ring.size > MAX_RECORDS) ring.removeFirst()
            logFile
        }
        try { file?.appendText(rec + "\n") } catch (_: Exception) {}
        // ERROR-уровень: единственные Log-вызовы, которые R8 не вырезает в release
        Log.e(TAG, rec)
    }

    /**
     * Native-safe маркер: СРАЗУ и синхронно (fsync) в filesDir/diag/diag-trace.log,
     * до любых буферов. Единственный способ увидеть последние шаги перед native-крашем
     * (libwebrtc SIGSEGV обходит Java-хендлеры и потерянные buffer'ы). Вызывать прямо
     * перед тяжёлыми native-вызовами и вокруг опасных операций.
     */
    fun marker(point: String, detail: String = "") {
        val rec = "${fmt.format(Date())} M $point" + if (detail.isNotEmpty()) " | $detail" else ""
        log(point + " [m]", detail)
        val f = synchronized(lock) { traceFile } ?: return
        try {
            java.io.FileOutputStream(f, true).use { fos ->
                fos.write((rec + "\n").toByteArray())
                fos.flush()
                try { fos.fd.sync() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun error(point: String, t: Throwable, detail: String = "") {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()
        val head = stack.lineSequence().take(12).joinToString(" ⏎ ")
        log("ERROR $point", ((if (detail.isNotEmpty()) "$detail | " else "") + head))
        // полный стек — только в файл (в ring — компактно, чтобы не вытеснить трассу)
        val file = synchronized(lock) { logFile }
        try { file?.appendText("FULL-STACK $point\n$stack\n") } catch (_: Exception) {}
    }

    /** try/catch-обёртка «залогировать и перевызовить как раньше» — семантику не меняет. */
    inline fun <R> traced(point: String, detail: () -> String = { "" }, block: () -> R): R =
        try {
            block()
        } catch (e: Throwable) {
            error("rethrow $point", e, detail())
            throw e
        }

    fun dump(): String = synchronized(lock) { ring.joinToString("\n") }

    fun pendingCrashFiles(): List<File> =
        (synchronized(lock) { crashDir })?.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.name.startsWith("diag-crash-") }
            ?.sortedBy { it.name } ?: emptyList()

    /** URL для лога: без query-строки — там бывают токены. */
    fun url(u: String): String = u.substringBefore('?')

    private fun installCrashHandler(crashDir: File, sessionFile: File?, sid: String) {
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val f = File(crashDir, "diag-crash-${stampFmt.format(Date())}.log")
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val sb = StringBuilder()
                sb.append("session=").append(sid)
                    .append(" pid=").append(Process.myPid())
                    .append(" thread=").append(thread.name)
                    .append(" time=").append(Date()).append('\n')
                sb.append("=== exception ===\n").append(sw)
                sb.append("=== ring buffer (${MAX_RECORDS}) ===\n").append(dump()).append('\n')
                f.writeText(sb.toString())
                try { sessionFile?.writeText("$sid|CRASHED|${e.javaClass.name}: ${e.message}") } catch (_: Exception) {}
                Log.e(TAG, "UNCAUGHT on ${thread.name}: $e -> ${f.absolutePath}", e)
            } catch (_: Throwable) { /* native/low-mem: не зацикливать хендлер */ }
            // делегируем прежнему дефолтному (обычный kill процесса/платформенный репорт)
            prevHandler?.uncaughtException(thread, e)
        }
    }
}
