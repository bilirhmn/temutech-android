package id.ac.ceh

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import id.ac.ceh.ui.EditorAccess
import id.ac.ceh.ui.EventEditorScreen
import id.ac.ceh.ui.HubTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

class CreateFragment : Fragment() {
    private val hub by activityViewModels<HubViewModel>()
    private var timeDialog: Dialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            id = R.id.content
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val nav = findNavController()
        val entry = nav.getBackStackEntry(R.id.createFragment)
        val handle = entry.savedStateHandle
        val owner = viewLifecycleOwner
        val eventId = arguments?.getLong("eventId") ?: 0L
        val token = handle.get<String>("editorToken") ?: UUID.randomUUID().toString().also { handle["editorToken"] = it }
        val categories = resources.getStringArray(R.array.categories).toList()
        val draft = mutableStateOf(handle.get<Bundle>("editorDraft")?.toDraft() ?: EventDraft())
        val state = mutableStateOf(EditorAccess.Loading)
        val busy = mutableStateOf(hub.busy.value)
        val issue = mutableStateOf<DraftIssue?>(null)

        fun store(value: EventDraft) {
            handle["editorDraft"] = value.toBundle()
            handle["editorCoordinates"] = value.point()?.let { doubleArrayOf(it.latitude, it.longitude) }
            draft.value = value
            if (issue.value != null) issue.value = value.issue()
        }
        fun isCurrent() = nav.currentBackStackEntry === entry && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        fun canEdit() = isCurrent() && !hub.busy.value && hub.savedEditor.value != token && hub.user.value?.let { user ->
            eventId == 0L || hub.events.value.any { it.id == eventId && it.isOwnedBy(user) }
        } == true

        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hub.catalogReady, hub.events, hub.user, hub.busy) { ready, events, user, writing ->
                    busy.value = writing
                    val event = events.firstOrNull { it.id == eventId && it.isOwnedBy(user) }
                    when {
                        user == null -> EditorAccess.Unavailable
                        eventId == 0L -> EditorAccess.Allowed
                        event != null -> {
                            if (!handle.contains("editorDraft")) store(event.toDraft())
                            EditorAccess.Allowed
                        }
                        !ready -> EditorAccess.Loading
                        else -> EditorAccess.Unavailable
                    }
                }.collect { state.value = it }
            }
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    handle.getStateFlow<DoubleArray?>(MapPick.RESULT, null).collect { result ->
                        if (!isCurrent() || result == null) return@collect
                        result.takeIf { it.size == 2 }?.let { validPoint(it[0], it[1]) }?.let {
                            if (canEdit()) store(draft.value.copy(latitude = it.latitude, longitude = it.longitude))
                        }
                        handle[MapPick.RESULT] = null as DoubleArray?
                    }
                }
                launch {
                    hub.savedEditor.collect { saved ->
                        if (saved == token && isCurrent()) nav.popBackStack()
                    }
                }
            }
        }
        (view as ComposeView).setContent {
            HubTheme {
                EventEditorScreen(draft.value, eventId != 0L, state.value, busy.value, issue.value, categories,
                    onDraft = { if (canEdit()) store(it) },
                    onTime = { end ->
                        if (canEdit()) pickTime(if (end) draft.value.end ?: draft.value.start else draft.value.start) { selected ->
                            if (canEdit()) store(if (end) draft.value.copy(end = selected) else draft.value.copy(start = selected))
                        }
                    },
                    onMap = { if (canEdit()) nav.navigate(R.id.mapFragment, bundleOf(MapPick.MODE to true)) },
                    onCancel = { if (isCurrent() && !hub.busy.value) nav.popBackStack() },
                    onSubmit = {
                        if (canEdit()) {
                            val current = draft.value
                            issue.value = current.issue()
                            current.toEvent(eventId)?.let { hub.saveEditor(token, it) }
                        }
                    },
                )
            }
        }
    }

    private fun pickTime(initial: Long?, accept: (Long) -> Unit) {
        val context = requireContext()
        val calendar = Calendar.getInstance().apply { if (initial != null) timeInMillis = initial }
        timeDialog?.dismiss()
        timeDialog = DatePickerDialog(context, { _, year, month, day ->
            timeDialog = TimePickerDialog(context, { _, hour, minute ->
                calendar.clear()
                calendar.set(year, month, day, hour, minute)
                accept(calendar.timeInMillis)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(context)).apply { show() }
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply { show() }
    }

    override fun onDestroyView() {
        timeDialog?.dismiss()
        timeDialog = null
        super.onDestroyView()
    }
}

private fun EventDraft.toBundle(): Bundle = Bundle().apply {
    putString("title", title)
    putString("description", description)
    putInt("category", category)
    start?.let { putLong("start", it) }
    end?.let { putLong("end", it) }
    putString("place", place)
    latitude?.let { putDouble("latitude", it) }
    longitude?.let { putDouble("longitude", it) }
    putString("imageUrl", imageUrl)
}

private fun Bundle.toDraft() = EventDraft(
    getString("title").orEmpty(), getString("description").orEmpty(), getInt("category"),
    getLong("start").takeIf { containsKey("start") }, getLong("end").takeIf { containsKey("end") },
    getString("place").orEmpty(), getDouble("latitude").takeIf { containsKey("latitude") },
    getDouble("longitude").takeIf { containsKey("longitude") }, getString("imageUrl").orEmpty(),
)
