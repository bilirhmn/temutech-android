package id.ac.ceh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import id.ac.ceh.ui.EventDetailScreen
import id.ac.ceh.ui.EventDetailUiState
import id.ac.ceh.ui.HubTheme
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import id.ac.ceh.data.DICODING_SOURCE
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class EventDetailFragment : Fragment() {
    private val hub by activityViewModels<HubViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            id = R.id.content
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val id = requireArguments().getLong("eventId")
        val nav = findNavController()
        val owner = viewLifecycleOwner
        val entry = nav.getBackStackEntry(R.id.eventDetailFragment)
        fun isCurrent() = nav.currentBackStackEntry === entry && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        fun canManage() = isCurrent() && !hub.busy.value && hub.events.value.any { it.id == id && it.isOwnedBy(hub.user.value) }
        val categories = resources.getStringArray(R.array.categories)
        val state = mutableStateOf<EventDetailUiState?>(null)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hub.events, hub.personalEvents, hub.busy) { events, personal, busy ->
                    events.firstOrNull { it.id == id }?.let { event ->
                        EventDetailUiState(event.title, event.category(categories),
                            listOfNotNull(event.displayDate(event.startTime), event.endTime?.let(event::displayDate)).joinToString(" – "),
                            if (event.isDicodingOnline) getString(R.string.dicoding_online) else event.location,
                            event.description, event.imageUrl, personal.bookmarks.orEmpty().any { it.id == id },
                            personal.attending.orEmpty().any { it.id == id }, event.isOwnedBy(personal.account), busy,
                            event.source == DICODING_SOURCE,
                            personal.account == null || personal.bookmarks != null)
                    }
                }.collect { state.value = it }
            }
        }
        fun signedIn(action: () -> Unit) {
            if (!isCurrent()) return
            if (hub.user.value == null) nav.navigate(R.id.authFragment) else action()
        }
        (view as ComposeView).setContent {
            HubTheme {
                EventDetailScreen(state.value,
                    onBookmark = { signedIn { if (state.value?.personalActionsReady == true) hub.bookmark(id) } },
                    onRsvp = { signedIn { state.value?.takeIf { it.personalActionsReady }?.let { hub.attend(id, !it.attending) } } },
                    onEdit = {
                        if (canManage()) {
                            nav.navigate(R.id.createFragment, bundleOf("eventId" to id))
                        }
                    },
                    onDelete = {
                        if (canManage()) {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.delete_confirm)
                                .setMessage(R.string.delete_confirm_message)
                                .setNegativeButton(R.string.map_cancel, null)
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    if (canManage()) owner.lifecycleScope.launch {
                                        if (hub.delete(id) && isCurrent()) nav.popBackStack()
                                    }
                                }.show()
                        }
                    },
                )
            }
        }
    }
}
