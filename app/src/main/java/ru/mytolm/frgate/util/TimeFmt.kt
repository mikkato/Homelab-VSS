package ru.mytolm.frgate.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** epoch-seconds (Float, UTC) → форматирование в локальном часовом поясе устройства.
 *  SimpleDateFormat не потокобезопасен (вызовы из UI и сервиса одновременно) — создаём на вызов. */
object TimeFmt {
    private fun timeFmt() = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun dateTimeFmt() = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private fun dateFmt() = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private fun fileFmt() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    fun hm(ts: Double): String = timeFmt().format(Date((ts * 1000).toLong()))
    fun dt(ts: Double): String = dateTimeFmt().format(Date((ts * 1000).toLong()))
    fun date(ts: Double): String = dateFmt().format(Date((ts * 1000).toLong()))
    fun fileStamp(ms: Long = System.currentTimeMillis()): String = fileFmt().format(Date(ms))

    fun duration(sec: Double?): String {
        if (sec == null) return "—"
        val s = sec.toLong()
        return if (s < 60) "%d с".format(s) else "%d мин %02d с".format(s / 60, s % 60)
    }

    /** Группа заголовка ленты: «Сегодня» / «Вчера» / дата. */
    fun groupLabel(ts: Double): String {
        val c = Calendar.getInstance().apply { timeInMillis = (ts * 1000).toLong() }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(c, today) -> "Сегодня"
            sameDay(c, yesterday) -> "Вчера"
            else -> dateFmt().format(Date((ts * 1000).toLong()))
        }
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** Период в днях назад → epoch-секунды (для since). */
    fun daysAgo(days: Int): Long =
        System.currentTimeMillis() / 1000L - TimeUnit.DAYS.toSeconds(days.toLong())

    fun hourOf(ts: Double): Int =
        Calendar.getInstance().apply { timeInMillis = (ts * 1000).toLong() }
            .get(Calendar.HOUR_OF_DAY)

    /** currently в минутах от полуночи для фильтра тихих часов (с переходом через полночь).
     *  from == to трактуется как «тишина круглосуточно» (выбор «22:00–22:00» = весь день). */
    fun inQuietHours(nowMinutes: Int, from: Int, to: Int): Boolean {
        if (from < 0 || to < 0) return false
        return when {
            from == to -> true
            from <= to -> nowMinutes in from until to
            else -> nowMinutes >= from || nowMinutes < to
        }
    }
}
