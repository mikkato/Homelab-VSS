package ru.mytolm.frgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.mytolm.frgate.*
import ru.mytolm.frgate.R
import ru.mytolm.frgate.data.toReviewSummary
import ru.mytolm.frgate.data.toCameraInfo
import ru.mytolm.frgate.frigate.ReviewSummary
import ru.mytolm.frgate.util.Diag
import ru.mytolm.frgate.util.TimeFmt

/** §3.3 Review: лента с группировкой по времени, метки категорий, отметка прочитанного. */
@Composable
fun ReviewScreen(nav: NavHostController) {
    // top10-10: состояние и загрузка — в ReviewViewModel (StateFlow), экран только рисует
    val vm: ReviewViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val items = st.items
    val cameras = st.cameras

    Scaffold(topBar = {
        ScreenHeader(stringResource(R.string.tab_review), actions = {
            if (cameras.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(st.camFilter ?: stringResource(R.string.filter_camera))
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.preset_all)) }, onClick = {
                            vm.setCamFilter(null); expanded = false
                        })
                        cameras.forEach { c ->
                            DropdownMenuItem(text = { Text(ru.mytolm.frgate.util.camName(c)) }, onClick = {
                                vm.setCamFilter(c); expanded = false
                            })
                        }
                    }
                }
            }
        })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            OfflineBanner(st.offline)
            CachedBadge(st.fromCache)
            ErrorRetryBanner(st.error) { vm.refresh() }
            if (items.isNotEmpty()) {
                Button(
                    onClick = { vm.markAllViewed() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(stringResource(R.string.accept_all)) }
            }
            if (st.loading && items.isEmpty()) {
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
                                Diag.log("tap.reviewCard", "reviewId=${rev.id} camera=${rev.camera} severity=${rev.severity} mainEventId=$evId")
                                if (evId != null) Diag.traced("nav.tap.reviewToEvent") {
                                    nav.navigate(Routes.event(evId))
                                }
                            }, onToggleViewed = { vm.toggleViewed(rev) })
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
                Text(ru.mytolm.frgate.util.camName(rev.camera), style = MaterialTheme.typography.bodyMedium)
                val labels = ru.mytolm.frgate.util.LabelsRu.labelsRu(rev.labels.distinct())
                if (labels.isNotBlank()) {
                    Text(labels, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(TimeFmt.hm(rev.startTime), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleViewed) {
                Icon(if (rev.viewed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = stringResource(if (rev.viewed) R.string.cd_mark_unviewed else R.string.cd_mark_viewed),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
