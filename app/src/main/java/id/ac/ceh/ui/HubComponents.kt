package id.ac.ceh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.ac.ceh.R

@Composable
internal fun HubButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    Button(
        onClick = onClick, shape = shape,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .border(dimensionResource(R.dimen.hub_focus_ring),
                if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .padding(4.dp)
            .heightIn(min = dimensionResource(R.dimen.hub_min_target)),
    ) { Text(label, textAlign = TextAlign.Center) }
}

@Composable
internal fun HubEventCard(
    title: String, metadata: String, category: String, actionLabel: String,
    onOpen: () -> Unit, modifier: Modifier = Modifier, isSelected: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, colors.outline),
        modifier = modifier.fillMaxWidth().semantics { selected = isSelected },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(dimensionResource(R.dimen.hub_space_medium)),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.hub_space_small)),
        ) {
            Surface(shape = MaterialTheme.shapes.small, color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer) {
                Text(category, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(metadata, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            OutlinedButton(onClick = onOpen, shape = MaterialTheme.shapes.small,
                border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outline),
                modifier = Modifier.heightIn(min = dimensionResource(R.dimen.hub_min_target))
                    .onFocusChanged { focused = it.isFocused }) {
                Text(actionLabel, textAlign = TextAlign.Center)
            }
        }
    }
}

internal enum class HubPanelState { Loading, Empty, Error }

@Composable
internal fun HubStatusPanel(
    state: HubPanelState, title: String, message: String, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (container, text) = when (state) {
        HubPanelState.Loading -> colors.primaryContainer to colors.onPrimaryContainer
        HubPanelState.Empty -> colors.surfaceVariant to colors.onSurfaceVariant
        HubPanelState.Error -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container, contentColor = text) {
        Column(
            Modifier.padding(dimensionResource(R.dimen.hub_space_medium))
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.hub_space_small)),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (state == HubPanelState.Loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = text, trackColor = container)
            }
            if (actionLabel != null && onAction != null) HubButton(actionLabel, onAction)
        }
    }
}
