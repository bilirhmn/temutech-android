package id.ac.ceh.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import id.ac.ceh.R

/** Debug-only showcase launched directly via ADB; not part of the app's navigation. */
internal class HubPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewAction = { Toast.makeText(this, R.string.hub_preview_clicked, Toast.LENGTH_SHORT).show() }
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HubTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        val spacing = dimensionResource(R.dimen.hub_space_medium)
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                            .padding(spacing), verticalArrangement = Arrangement.spacedBy(spacing)) {
                            Text(stringResource(R.string.hub_preview_title), style = MaterialTheme.typography.headlineMedium)
                            HubEventCard(stringResource(R.string.hub_preview_sample),
                                stringResource(R.string.hub_preview_metadata), stringResource(R.string.hub_preview_category),
                                stringResource(R.string.hub_preview_open), previewAction, isSelected = true)
                            HubStatusPanel(HubPanelState.Loading, stringResource(R.string.hub_preview_loading),
                                stringResource(R.string.hub_preview_loading_hint))
                            HubStatusPanel(HubPanelState.Empty, stringResource(R.string.hub_preview_empty),
                                stringResource(R.string.hub_preview_empty_hint))
                            HubStatusPanel(HubPanelState.Error, stringResource(R.string.hub_preview_error),
                                stringResource(R.string.hub_preview_error_hint),
                                actionLabel = stringResource(R.string.hub_preview_retry), onAction = previewAction)
                        }
                    }
                }
            }
        })
    }
}
