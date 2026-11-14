package ru.mytolm.frgate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import ru.mytolm.frgate.*
import ru.mytolm.frgate.R

/**
 * §3.5 Explore/поиск: семантический с fallback на events?query=, фильтры, бесконечная прокрутка.
 * top10-10: всё состояние и загрузка — в ExploreViewModel (StateFlow);composition только рисует,
 * подгрузка по краю — через snapshotFlow(gridState.canScrollForward), не из лямбды items().
 */
@Composable
fun ExploreScreen(nav: NavHostController) {
    val vm: ExploreViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.init() }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.tab_explore)) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OfflineBanner(st.offline)
            CachedBadge(st.fromCache)
            ErrorRetryBanner(st.error) { vm.search() }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = st.query,
                    onValueChange = { vm.setQuery(it) },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.search() }),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.cd_search)) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(modifier = Modifier.height(56.dp), onClick = { vm.search() }) { Text(stringResource(R.string.search_button), maxLines = 1, softWrap = false) }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(modifier = Modifier.height(40.dp), selected = st.camFilter == null, onClick = { vm.setCamFilter(null) }, label = { Text(stringResource(R.string.filter_camera) + ": " + (st.camFilter ?: stringResource(R.string.preset_all)), maxLines = 1, softWrap = false) })
                st.cameras.forEach { c ->
                    FilterChip(modifier = Modifier.height(40.dp), selected = st.camFilter == c, onClick = { vm.setCamFilter(c) }, label = { Text(c, maxLines = 1, softWrap = false) })
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(R.string.preset_hour to 0, R.string.preset_today to 1, R.string.preset_week to 2, R.string.preset_all to 3)
                    .forEach { (res, i) ->
                        FilterChip(modifier = Modifier.height(40.dp), selected = st.preset == i, onClick = { vm.setPreset(i) }, label = { Text(stringResource(res), maxLines = 1, softWrap = false) })
                    }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(modifier = Modifier.height(40.dp), selected = st.labelFilter == null, onClick = { vm.setLabelFilter(null) }, label = { Text(stringResource(R.string.preset_all), maxLines = 1, softWrap = false) })
                st.labels.forEach { l ->
                    FilterChip(modifier = Modifier.height(40.dp), selected = st.labelFilter == l, onClick = { vm.setLabelFilter(l) }, label = { Text(ru.mytolm.frgate.util.LabelsRu.labelRu(l), maxLines = 1, softWrap = false) })
                }
            }
            if (st.semanticOff) {
                Text(
                    stringResource(R.string.search_semantic_off),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            if (st.events.isEmpty() && !st.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // top10-7: пустое состояние объясняет действие, а не просто «нет данных»
                    Text(
                        stringResource(if (st.query.isBlank() && st.camFilter == null && st.labelFilter == null) R.string.search_empty_hint else R.string.rec_no_data),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val gridState = rememberLazyGridState()
                LaunchedEffect(gridState) {
                    snapshotFlow { gridState.canScrollForward }.collect { forward ->
                        if (!forward) vm.loadMore()
                    }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(st.events, key = { _, e -> e.id }) { _, e ->
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
                    }
                }
            }
            if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
