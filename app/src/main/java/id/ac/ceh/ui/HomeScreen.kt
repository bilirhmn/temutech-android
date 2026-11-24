package id.ac.ceh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import id.ac.ceh.R
import id.ac.ceh.data.DICODING_ONLINE

internal data class HomeEventRow(val id: Long, val title: String, val category: String, val date: String,
    val place: String, val imageUrl: String?, val isDicoding: Boolean)

internal data class HomeUiState(val query: String, val category: Int, val rows: List<HomeEventRow>,
    val busy: Boolean, val hasLocation: Boolean, val city: String = "", val cities: List<String> = emptyList(), val hasOnline: Boolean = false)

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    categories: List<String>,
    onQuery: (String) -> Unit,
    onCategory: (Int) -> Unit,
    onCity: (String) -> Unit,
    onLocate: () -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(state.query, onQuery, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.search)) })
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories.size) { index ->
                            FilterChip(selected = state.category == index, onClick = { onCategory(index) },
                                label = { Text(categories[index]) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    if (state.cities.isNotEmpty() || state.city.isNotEmpty() || state.hasOnline) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (state.city.isEmpty()) stringResource(R.string.dicoding_all_cities)
                                    else if (state.city == DICODING_ONLINE) stringResource(R.string.dicoding_online)
                                    else stringResource(R.string.dicoding_city, state.city))
                            }
                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.dicoding_all_cities)) },
                                    onClick = { onCity(""); expanded = false })
                                if (state.hasOnline) DropdownMenuItem(text = { Text(stringResource(R.string.dicoding_online)) },
                                    onClick = { onCity(DICODING_ONLINE); expanded = false })
                                state.cities.forEach { city ->
                                    DropdownMenuItem(text = { Text(city) }, onClick = { onCity(city); expanded = false })
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(if (state.hasLocation) R.string.nearby_active else R.string.nearby))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.dicoding_import))
                        }
                        Button(onClick = onCreate, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.create))
                        }
                    }
                }
            }
            if (state.busy) item(key = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.rows.isEmpty() && !state.busy) item(key = "empty") {
                Text(stringResource(R.string.no_events), style = MaterialTheme.typography.bodyLarge)
            }
            items(state.rows, key = { it.id }) { row ->
                EventListCard(row, onOpen)
            }
        }
    }
}

@Composable
internal fun EventListCard(row: HomeEventRow, onOpen: (Long) -> Unit) {
    OutlinedCard(onClick = { onOpen(row.id) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EventArtwork(row.imageUrl, Modifier.size(80.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.category, style = MaterialTheme.typography.labelSmall)
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Text(row.date, style = MaterialTheme.typography.bodyMedium)
                if (row.place.isNotBlank()) Text(row.place, style = MaterialTheme.typography.bodyMedium)
                if (row.isDicoding) Text(stringResource(R.string.dicoding_source), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun EventArtwork(url: String?, modifier: Modifier = Modifier) {
    val placeholder = painterResource(R.drawable.hub_event_placeholder)
    AsyncImage(model = url, contentDescription = null, modifier = modifier,
        placeholder = placeholder, fallback = placeholder, error = placeholder, contentScale = ContentScale.Crop)
}
