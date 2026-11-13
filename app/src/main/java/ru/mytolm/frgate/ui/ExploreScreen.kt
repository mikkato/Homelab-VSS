package ru.mytolm.frgate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.mytolm.frgate.*
import ru.mytolm.frgate.R
import ru.mytolm.frgate.frigate.FrigateEvent
import ru.mytolm.frgate.util.TimeFmt

/** §3.5 Explore/поиск: семантический с fallback на events?query=, фильтры, бесконечная прокрутка. */
@Composable
fun ExploreScreen(nav: NavHostController) {
    var query by remember { mutableStateOf("") }
    var camFilter by remember { mutableStateOf<String?>(null) }
    var labelFilter by remember { mutableStateOf<String?>(null) }
    var preset by remember { mutableStateOf(2) } // 0=час,1=сегодня,2=7дней,3=всё
    var events by remember { mutableStateOf<List<FrigateEvent>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var exhausted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var semanticOff by remember { mutableStateOf(false) }
    var cameras by remember { mutableStateOf<List<String>>(emptyList()) }
    var labels by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            cameras = ru.mytolm.frgate.frigate.ConfigParse.cameras(appContainer.client.config()).map { it.name }
            labels = appContainer.client.labels()
        } catch (_: Exception) {
        }
    }

    val since: Long? = when (preset) {
        0 -> TimeFmt.daysAgo(0) - 86400 + 86400 // час назад
        1 -> run {
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
            }
            c.timeInMillis / 1000
        }
        2 -> TimeFmt.daysAgo(7)
        else -> null
    }.let { if (preset == 0) System.currentTimeMillis() / 1000 - 3600 else it }

    suspend fun fetchPage(reset: Boolean) {
        if (loading || (exhausted && !reset)) return
        loading = true
        try {
            val off = if (reset) 0 else offset
            val page: List<FrigateEvent>? = if (query.isNotBlank() && reset) {
                appContainer.client.search(query)
            } else null
            val list = if (page != null && page.isNotEmpty()) {
                semanticOff = false
                page
            } else {
                if (query.isNotBlank() && page != null && page.isEmpty() && offset == 0) semanticOff = true
                appContainer.client.events(
                    cameras = camFilter?.let { listOf(it) } ?: emptyList(),
                    labels = labelFilter?.let { listOf(it) } ?: emptyList(),
                    since = since,
                    limit = 60,
                    offset = off,
                    query = query.ifBlank { null }
                )
            }
            if (reset) { events = list; offset = 60 } else {
                events = events + list
                offset += 60
            }
            if (list.size < 60) exhausted = true
            offline = false
            // кэш в Room
            appContainer.db.eventDao().upsert(
                list.map {
                    ru.mytolm.frgate.data.EventEntity(
                        it.id, it.camera, it.label, it.sub_label, it.since, it.end_time, it.duration,
                        it.has_clip, it.has_snapshot, it.zones.joinToString(","), it.confidence,
                        it.retained, it.false_positive ?: false
                    )
                }
            )
        } catch (e: Exception) {
            offline = true
        }
        loading = false
    }

    LaunchedEffect(searchTick) { if (searchTick > 0) fetchPage(true) }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.tab_explore)) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OfflineBanner(offline)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { searchTick++ }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(modifier = Modifier.height(56.dp), onClick = { searchTick++ }) { Text(stringResource(R.string.search_button), maxLines = 1, softWrap = false) }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(modifier = Modifier.height(40.dp), selected = camFilter == null, onClick = { camFilter = null; searchTick++ }, label = { Text(stringResource(R.string.filter_camera) + ": " + (camFilter ?: stringResource(R.string.preset_all)), maxLines = 1, softWrap = false) })
                cameras.forEach { c ->
                    FilterChip(modifier = Modifier.height(40.dp), selected = camFilter == c, onClick = { camFilter = c; searchTick++ }, label = { Text(c, maxLines = 1, softWrap = false) })
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(R.string.preset_hour to 0, R.string.preset_today to 1, R.string.preset_week to 2, R.string.preset_all to 3)
                    .forEach { (res, i) ->
                        FilterChip(modifier = Modifier.height(40.dp), selected = preset == i, onClick = { preset = i; searchTick++ }, label = { Text(stringResource(res), maxLines = 1, softWrap = false) })
                    }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(modifier = Modifier.height(40.dp), selected = labelFilter == null, onClick = { labelFilter = null; searchTick++ }, label = { Text(stringResource(R.string.preset_all), maxLines = 1, softWrap = false) })
                labels.forEach { l ->
                    FilterChip(modifier = Modifier.height(40.dp), selected = labelFilter == l, onClick = { labelFilter = l; searchTick++ }, label = { Text(ru.mytolm.frgate.util.LabelsRu.labelRu(l), maxLines = 1, softWrap = false) })
                }
            }
            if (semanticOff) {
                Text(
                    stringResource(R.string.search_semantic_off),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            if (events.isEmpty() && !loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.rec_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(events, key = { _, e -> e.id }) { idx, e ->
                        AsyncImage(
                            imageLoader = appContainer.imageLoader,
                            model = appContainer.client.absoluteUrl("/api/events/${e.id}/thumbnail.jpg"),
                            contentDescription = e.label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { nav.navigate(Routes.event(e.id)) }
                        )
                        if (idx == events.size - 5 && !loading && !exhausted) {
                            scope.launch { fetchPage(false) }
                        }
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
