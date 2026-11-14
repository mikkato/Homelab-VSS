package ru.mytolm.frgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.mytolm.frgate.*
import ru.mytolm.frgate.R
import ru.mytolm.frgate.data.toReviewSummary
import ru.mytolm.frgate.data.toCameraInfo
import ru.mytolm.frgate.frigate.ReviewSummary
import ru.mytolm.frgate.util.TimeFmt

/** §3.3 Review: лента с группировкой по времени, метки категорий, отметка прочитанного. */
@Composable
fun ReviewScreen(nav: NavHostController) {
    var items by remember { mutableStateOf<List<ReviewSummary>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    var fromCache by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var camFilter by remember { mutableStateOf<String?>(null) }
    var cameras by remember { mutableStateOf<List<String>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(camFilter, refresh) {
        loading = true
        try {
            val cfg = appContainer.client.config()
            cameras = ru.mytolm.frgate.frigate.ConfigParse.cameras(cfg).map { it.name }
            items = appContainer.client.review(
                cameras = camFilter?.let { listOf(it) } ?: emptyList()
            ).filter { !it.viewed }
            offline = false
            fromCache = false
            // есть непросмотренные -> рамки показать; все приняты -> снять (переживает перезапуск)
            appContainer.boxesHidden.value = items.none { !it.viewed }
            // офлайн-кэш
            appContainer.db.reviewDao().upsert(
                items.map {
                    ru.mytolm.frgate.data.ReviewEntity(
                        id = it.id, camera = it.camera, thumbTime = it.thumbTime,
                        severity = it.severity, viewed = it.viewed,
                        mainEventId = it.mainEventId ?: "",
                        labelsCsv = it.labels.distinct().joinToString(","),
                        json = ""
                    )
                }
            )
        } catch (e: Exception) {
            android.util.Log.w("FRGateReview", "refresh failed", e)
            offline = true
            // top10-1: фолбэк на кэш Room
            try {
                val cached = appContainer.db.reviewDao().recentList(100)
                    .map { it.toReviewSummary() }
                    .filter { r -> !r.viewed && (camFilter == null || r.camera == camFilter) }
                if (cached.isNotEmpty()) { items = cached; fromCache = true }
                val cachedCams = appContainer.db.cameraDao().allList().map { it.name }
                if (cachedCams.isNotEmpty()) cameras = cachedCams
            } catch (_: Exception) { }
        }
        loading = false
    }

    Scaffold(topBar = {
        ScreenHeader(stringResource(R.string.tab_review), actions = {
            if (cameras.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(camFilter ?: stringResource(R.string.filter_camera))
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.preset_all)) }, onClick = {
                            camFilter = null; expanded = false
                        })
                        cameras.forEach { c ->
                            DropdownMenuItem(text = { Text(c) }, onClick = {
                                camFilter = c; expanded = false
                            })
                        }
                    }
                }
            }
        })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            OfflineBanner(offline)
            CachedBadge(fromCache)
            if (items.isNotEmpty()) {
                Button(
                    onClick = {
                        val ids = items.map { it.id }
                        scope.launch {
                            val r = runCatching { appContainer.client.markViewed(ids) }
                            android.util.Log.d("FRGateAccept", "ids=${ids.size} ok=${r.isSuccess} ${r.exceptionOrNull()?.message}")
                            if (r.isSuccess) appContainer.boxesHidden.value = true
                            refresh++
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(stringResource(R.string.accept_all)) }
            }
            if (loading && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.rec_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val grouped = items.groupBy { TimeFmt.groupLabel(it.startTime) }
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grouped.forEach { (label, group) ->
                        item {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                            )
                        }
                        items(group, key = { it.id }) { rev ->
                            ReviewCard(rev, onClick = {
                                val evId = rev.mainEventId
                                if (evId != null) nav.navigate(Routes.event(evId))
                            }, onToggleViewed = {
                                scope.launch {
                                    try {
                                        if (rev.viewed) appContainer.client.unmarkViewed(rev.id)
                                        else appContainer.client.markViewed(listOf(rev.id))
                                        refresh++
                                    } catch (_: Exception) {
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(rev: ReviewSummary, onClick: () -> Unit, onToggleViewed: () -> Unit) {
    val sevColor = when (rev.severity) {
        "alarm" -> Color(0xFFD0342C)     // красный
        "motion" -> Color(0xFF2D6CDF)    // синий
        else -> Color(0xFFE0A82E)        // жёлтый (detection)
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                            imageLoader = appContainer.imageLoader,
                model = rev.mainEventId?.let { appContainer.client.absoluteUrl("/api/events/$it/thumbnail.jpg") },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = sevColor.copy(alpha = 0.15f)) {
                        Text(
                            ru.mytolm.frgate.util.LabelsRu.severityRu(rev.severity),
                            color = sevColor,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (!rev.viewed) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(8.dp)) {}
                    }
                }
                Text(rev.camera, style = MaterialTheme.typography.bodyMedium)
                val labels = ru.mytolm.frgate.util.LabelsRu.labelsRu(rev.labels.distinct())
                if (labels.isNotBlank()) {
                    Text(labels, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(TimeFmt.hm(rev.startTime), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleViewed) {
                Text(if (rev.viewed) "✓" else "○")
            }
        }
    }
}
