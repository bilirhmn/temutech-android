package id.ac.ceh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import id.ac.ceh.R

internal data class EventDetailUiState(val title: String, val category: String, val date: String,
    val place: String?, val description: String?, val imageUrl: String?, val bookmarked: Boolean,
    val attending: Boolean, val isOwner: Boolean, val busy: Boolean, val isDicoding: Boolean,
    val personalActionsReady: Boolean = true)

@Composable
internal fun EventDetailScreen(
    state: EventDetailUiState?, onBookmark: () -> Unit, onRsvp: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state == null) {
                Text(stringResource(R.string.detail_empty), style = MaterialTheme.typography.bodyLarge)
            } else {
                EventArtwork(state.imageUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium))
                Text(state.category, style = MaterialTheme.typography.labelLarge)
                if (state.isDicoding) Text(stringResource(R.string.dicoding_source), style = MaterialTheme.typography.labelMedium)
                Text(state.title, style = MaterialTheme.typography.headlineMedium)
                Text(state.date, style = MaterialTheme.typography.bodyMedium)
                state.place?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                state.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                OutlinedButton(onClick = onBookmark, enabled = !state.busy && state.personalActionsReady, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.bookmarked) R.string.unbookmark else R.string.bookmark))
                }
                Button(onClick = onRsvp, enabled = !state.busy && state.personalActionsReady, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.attending) R.string.cancel_rsvp else R.string.rsvp))
                }
                if (state.isOwner) {
                    OutlinedButton(onClick = onEdit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.edit))
                    }
                    TextButton(onClick = onDelete, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.delete), color = if (state.busy) LocalContentColor.current else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
