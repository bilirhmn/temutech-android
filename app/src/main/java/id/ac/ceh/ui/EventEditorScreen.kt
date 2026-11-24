package id.ac.ceh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.ac.ceh.DraftIssue
import id.ac.ceh.EventDraft
import id.ac.ceh.R
import id.ac.ceh.dateLabel

internal enum class EditorAccess { Loading, Unavailable, Allowed }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun EventEditorScreen(
    draft: EventDraft,
    editing: Boolean,
    access: EditorAccess,
    busy: Boolean,
    issue: DraftIssue?,
    categories: List<String>,
    onDraft: (EventDraft) -> Unit,
    onTime: (Boolean) -> Unit,
    onMap: () -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    val titleFocus = remember { FocusRequester() }
    val imageFocus = remember { FocusRequester() }
    val startView = remember { BringIntoViewRequester() }
    val endView = remember { BringIntoViewRequester() }
    val coordinatesView = remember { BringIntoViewRequester() }
    var categoryOpen by remember { mutableStateOf(false) }
    LaunchedEffect(issue, access) {
        if (access != EditorAccess.Allowed) return@LaunchedEffect
        when (issue) {
            DraftIssue.Title -> titleFocus.requestFocus()
            DraftIssue.Image -> imageFocus.requestFocus()
            DraftIssue.Start -> startView.bringIntoView()
            DraftIssue.End -> endView.bringIntoView()
            DraftIssue.Coordinates -> coordinatesView.bringIntoView()
            null -> Unit
        }
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (editing) R.string.edit else R.string.create), style = MaterialTheme.typography.headlineSmall)
            if (access != EditorAccess.Allowed) {
                Text(stringResource(if (access == EditorAccess.Loading) R.string.editor_loading else R.string.editor_unavailable))
                if (access == EditorAccess.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text(stringResource(R.string.map_cancel)) }
                return@Column
            }
            OutlinedTextField(draft.title, { onDraft(draft.copy(title = it.take(120))) },
                Modifier.fillMaxWidth().focusRequester(titleFocus), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.event_title)) }, isError = issue == DraftIssue.Title,
                supportingText = { if (issue == DraftIssue.Title) EditorError(R.string.required) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Box {
                EditorButton(stringResource(R.string.editor_category, categories.getOrElse(draft.category) { categories.first() }), !busy,
                    onClick = { categoryOpen = true })
                DropdownMenu(categoryOpen, onDismissRequest = { categoryOpen = false }) {
                    categories.forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            categoryOpen = false
                            onDraft(draft.copy(category = index))
                        })
                    }
                }
            }
            OutlinedTextField(draft.description, { onDraft(draft.copy(description = it.take(3000))) },
                Modifier.fillMaxWidth(), enabled = !busy, minLines = 2,
                label = { Text(stringResource(R.string.description)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Column(Modifier.bringIntoViewRequester(startView)) {
                EditorButton(draft.start?.let { stringResource(R.string.editor_start_value, dateLabel(it)) }
                    ?: stringResource(R.string.start_time), !busy, onClick = { onTime(false) })
                if (issue == DraftIssue.Start) EditorError(R.string.required)
            }
            Column(Modifier.bringIntoViewRequester(endView)) {
                EditorButton(draft.end?.let { stringResource(R.string.editor_end_value, dateLabel(it)) }
                    ?: stringResource(R.string.end_time), !busy, onClick = { onTime(true) })
                if (issue == DraftIssue.End) EditorError(R.string.invalid_end)
                if (draft.end != null) TextButton(enabled = !busy, onClick = { onDraft(draft.copy(end = null)) }) {
                    Text(stringResource(R.string.clear_end))
                }
            }
            OutlinedTextField(draft.place, { onDraft(draft.copy(place = it.take(180))) }, Modifier.fillMaxWidth(),
                enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.place)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            Column(Modifier.bringIntoViewRequester(coordinatesView)) {
                EditorButton(stringResource(R.string.pick_map), !busy, onClick = onMap)
                draft.point()?.let {
                    Text(stringResource(R.string.coordinates, it.latitude, it.longitude),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (issue == DraftIssue.Coordinates) EditorError(R.string.editor_invalid_coordinates)
                if (draft.latitude != null || draft.longitude != null) {
                    TextButton(enabled = !busy, onClick = { onDraft(draft.copy(latitude = null, longitude = null)) }) {
                        Text(stringResource(R.string.clear_location))
                    }
                }
            }
            OutlinedTextField(draft.imageUrl, { onDraft(draft.copy(imageUrl = it.take(2048))) },
                Modifier.fillMaxWidth().focusRequester(imageFocus), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.image_url)) }, isError = issue == DraftIssue.Image,
                supportingText = { if (issue == DraftIssue.Image) EditorError(R.string.invalid_url) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = onSubmit, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (editing) R.string.submit else R.string.create))
            }
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.map_cancel))
            }
        }
    }
}

@Composable
private fun EditorButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label)
    }
}

@Composable
private fun EditorError(label: Int) {
    Text(stringResource(label), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
