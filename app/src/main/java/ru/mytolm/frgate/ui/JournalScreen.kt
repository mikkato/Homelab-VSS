package ru.mytolm.frgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.mytolm.frgate.R
import ru.mytolm.frgate.Routes
import ru.mytolm.frgate.appContainer
import ru.mytolm.frgate.frigate.FrigateApiException
import ru.mytolm.frgate.frigate.FrigateEvent
import ru.mytolm.frgate.util.Diag
import ru.mytolm.frgate.util.LabelsRu
import ru.mytolm.frgate.util.TimeFmt
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * «Журнал»: календарь событий. Тап по дате — сводка обнаружений по субъектам
 * (label + sub_label) с группировкой и миниатюрами; тап по миниатюре — EventScreen.
 * Данные: события за последние JOURNAL_DAYS сут через events(since=…), пагинация offset.
 */
private const val JOURNAL_DAYS = 90
private const val PAGE = 250
private const val MAX_EVENTS = 1500

class JournalViewModel : ViewModel() {
    data class State(
        val loading: Boolean = false,
        val error: String? = null,
        val offline: Boolean = false,
        val month: YearMonth = YearMonth.now(),
        val events: List<FrigateEvent> = emptyList(),
        val selected: LocalDate = LocalDate.now(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var loaded = false

    fun init() {
        if (!loaded) load()
    }

    /** Субъект события: метка + подметка (распознанный человек/животное), если есть. */
    fun eventsByDate(d: LocalDate): List<FrigateEvent> {
        val z = ZoneId.systemDefault()
        return _state.value.events
            .filter { Instant.ofEpochSecond(it.start_time.toLong()).atZone(z).toLocalDate() == d }
            .sortedByDescending { it.start_time }
    }

    /** Дата → число событий (точки в календаре). */
    fun countsByDate(): Map<LocalDate, Int> {
        val z = ZoneId.systemDefault()
        return _state.value.events
            .groupingBy { Instant.ofEpochSecond(it.start_time.toLong()).atZone(z).toLocalDate() }
            .eachCount()
    }

    fun shiftMonth(delta: Int) = _state.update { it.copy(month = it.month.plusMonths(delta.toLong())) }

    fun selectDate(d: LocalDate) {
        _state.update { it.copy(selected = d, month = YearMonth.from(d)) }
    }

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val since = TimeFmt.daysAgo(JOURNAL_DAYS)
            val acc = mutableListOf<FrigateEvent>()
            var offlineErr = false
            var errMsg: String? = null
            try {
                var offset = 0
                while (acc.size < MAX_EVENTS) {
                    val page = appContainer.client.events(since = since, limit = PAGE, offset = offset)
                    acc += page
                    if (page.size < PAGE) break
                    offset += PAGE
                }
                loaded = true
            } catch (e: FrigateApiException) {
                offlineErr = e.code == 0
                errMsg = e.messageRu
            } catch (e: Exception) {
                Diag.log("journal.load.error", e.toString())
                offlineErr = true
                errMsg = "err"
            }
            _state.update {
                it.copy(
                    loading = false,
                    offline = offlineErr,
                    error = if (acc.isEmpty()) errMsg else null,
                    events = acc,
                )
            }
        }
    }
}

@Composable
fun JournalScreen(nav: NavHostController) {
    val vm: JournalViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.init() }

    val counts = remember(st.events) { vm.countsByDate() }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.tab_journal)) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OfflineBanner(st.offline)
            ErrorRetryBanner(st.error) { vm.load() }
            MonthStrip(st.month, counts, st.selected) { vm.shiftMonth(it) }
            val onDay: (LocalDate) -> Unit = { d ->
                Diag.log("tap.journalDate", "date=$d count=${counts[d] ?: 0}")
                vm.selectDate(d)
            }
            MonthGrid(st.month, counts, st.selected, onDay)
            HorizontalDivider(Modifier.padding(top = 6.dp))
            DaySummary(st.selected, counts[st.selected] ?: 0, vm.eventsByDate(st.selected), nav, st.loading)
        }
    }
}

@Composable
private fun MonthStrip(month: YearMonth, counts: Map<LocalDate, Int>, selected: LocalDate, onShift: (Int) -> Unit) {
    // UI приложения русский; локаль устройства может быть en — месяц форматируем по-русски
    val title = java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale("ru"))
        .format(month.atDay(1))
        .replaceFirstChar { it.uppercase() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        IconButton(onClick = { onShift(1) }) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

@Composable
private fun MonthGrid(month: YearMonth, counts: Map<LocalDate, Int>, selected: LocalDate, onDay: (LocalDate) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1 // Monday = 0
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            WEEKDAYS.forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { dow ->
                    val idx = (week * 7 + dow - offset).toLong()
                    val day = first.plusDays(idx)
                    val inMonth = YearMonth.from(day) == month
                    val n = counts[day] ?: 0
                    val isSelected = day == selected
                    val isToday = day == today
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isSelected -> scheme.primary
                                    isToday -> scheme.secondaryContainer
                                    else -> androidx.compose.ui.graphics.Color.Transparent
                                }
                            )
                            .clickable(enabled = inMonth && n > 0) { onDay(day) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (inMonth) day.dayOfMonth.toString() else "",
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    isSelected -> scheme.onPrimary
                                    isToday -> scheme.onSecondaryContainer
                                    inMonth -> scheme.onSurface
                                    else -> scheme.onSurfaceVariant
                                }
                            )
                            Box(Modifier.size(5.dp).padding(top = 1.dp).clip(CircleShape).background(
                                if (inMonth && n > 0) {
                                    if (isSelected) scheme.onPrimary.copy(alpha = 0.9f) else scheme.secondary
                                } else androidx.compose.ui.graphics.Color.Transparent
                            ))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySummary(date: LocalDate, dayCount: Int, events: List<FrigateEvent>, nav: NavHostController, loading: Boolean) {
    if (loading && events.isEmpty()) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp))
        return
    }
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                if (dayCount == 0) stringResource(R.string.journal_no_events) else stringResource(R.string.rec_no_data),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }
    val header = when (TimeFmt.groupLabel(events.first().start_time)) {
        "Сегодня" -> stringResource(R.string.journal_today)
        "Вчера" -> stringResource(R.string.journal_yesterday)
        else -> TimeFmt.date(events.first().start_time)
    }
    // Простой хронологический список: время · субъект · камера. Тап по строке — EventScreen.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Text(
                "$header · ${events.size}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
            )
        }
        items(events, key = { it.id }) { e ->
            val subject = "зафиксировано перемещение: " + LabelsRu.labelRu(e.label) +
                (e.sub_label?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        Diag.log("tap.journalRow", "eventId=${e.id} date=$date time=${TimeFmt.hm(e.start_time)} subject=$subject")
                        Diag.traced("nav.journalToEvent") { nav.navigate(Routes.event(e.id)) }
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    TimeFmt.hm(e.start_time),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    subject,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    ru.mytolm.frgate.util.camName(e.camera),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        }
    }
}
