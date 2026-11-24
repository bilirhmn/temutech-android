package id.ac.ceh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.ac.ceh.CloudProfileState
import id.ac.ceh.R

internal data class ProfileUiState(val account: String?, val rows: List<HomeEventRow> = emptyList())

@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    cloud: CloudProfileState?,
    onReloadCloud: () -> Unit,
    onSaveCloud: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        key(state.account) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("title") {
                    Text(stringResource(R.string.profile), Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall)
                }
                item("account") {
                    Text(stringResource(when (state.account) {
                        null -> R.string.guest
                        "demo" -> R.string.demo_account
                        else -> R.string.cloud_account
                    }),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyLarge)
                }
                if (state.account == null) {
                    item("guestMessage") {
                        Text(stringResource(R.string.profile_guest_message), style = MaterialTheme.typography.bodyMedium)
                    }
                    item("signIn") { HubButton(stringResource(R.string.sign_in), onSignIn) }
                } else {
                    item("signOut") {
                        OutlinedButton(onClick = onSignOut, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = dimensionResource(R.dimen.hub_min_target))) {
                            Text(stringResource(R.string.sign_out))
                        }
                    }
                    if (state.account.startsWith("firebase:")) {
                        item("cloudProfile") { CloudProfile(cloud, onReloadCloud, onSaveCloud) }
                    }
                    item("ownedHeading") {
                        Text(stringResource(R.string.my_events), Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium)
                    }
                    item("create") { HubButton(stringResource(R.string.create), onCreate) }
                    if (state.rows.isEmpty()) {
                        item("empty") {
                            Text(stringResource(R.string.profile_empty), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(state.rows, key = { it.id }) { row -> EventListCard(row, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun CloudProfile(state: CloudProfileState?, onReload: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(state?.name) { mutableStateOf(state?.name.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.cloud_profile_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        if (state == null || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state?.failed == true) {
            Text(stringResource(R.string.cloud_profile_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = onReload) { Text(stringResource(R.string.cloud_profile_retry)) }
        } else if (!state?.name.isNullOrBlank() && state?.busy == false) {
            Text(stringResource(R.string.cloud_profile_saved), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        OutlinedTextField(name, { if (it.codePointCount(0, it.length) <= 80) name = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = state?.busy == false,
            label = { Text(stringResource(R.string.cloud_profile_name)) })
        Button(onClick = { onSave(name.trim()) }, enabled = state?.busy == false && name.isNotBlank() && name.trim() != state.name,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.cloud_profile_save))
        }
    }
}
