package id.ac.ceh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.ac.ceh.R

internal data class SavedUiState(
    val account: String?,
    val attending: Boolean,
    val rows: List<HomeEventRow>?,
    val failed: Boolean = false,
)

@Composable
internal fun SavedScreen(
    state: SavedUiState,
    onSegment: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onBrowse: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val bookmarksScroll = rememberLazyListState()
    val attendingScroll = rememberLazyListState()
    val statusScroll = rememberLazyListState()
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = when {
                state.rows.isNullOrEmpty() -> statusScroll
                state.attending -> attendingScroll
                else -> bookmarksScroll
            },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.account == null) {
                item("guest") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.saved_guest), style = MaterialTheme.typography.bodyLarge)
                        HubButton(stringResource(R.string.sign_in), onSignIn)
                    }
                }
            } else {
                item("segments") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !state.attending, onClick = { onSegment(false) },
                            label = { Text(stringResource(R.string.saved_bookmarks)) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                        FilterChip(selected = state.attending, onClick = { onSegment(true) },
                            label = { Text(stringResource(R.string.saved_attending)) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    }
                }
                when {
                    state.failed -> item("failed") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SavedStatus(R.string.saved_load_failed)
                            HubButton(stringResource(R.string.saved_retry), onRetry)
                        }
                    }
                    state.rows == null -> item("loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SavedStatus(R.string.saved_loading)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    state.rows.isEmpty() -> item("empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SavedStatus(if (state.attending) R.string.saved_empty_attending else R.string.saved_empty_bookmarks)
                            HubButton(stringResource(R.string.home), onBrowse)
                        }
                    }
                    else -> items(state.rows, key = { it.id }) { row ->
                        EventListCard(row, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedStatus(label: Int) {
    Text(stringResource(label), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyLarge)
}
