package id.ac.ceh

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
import id.ac.ceh.data.Event
import id.ac.ceh.data.DICODING_SOURCE
import id.ac.ceh.data.DICODING_ONLINE
import id.ac.ceh.data.DICODING_ZONE
import java.util.TimeZone
import id.ac.ceh.ui.AuthScreen
import id.ac.ceh.ui.HubTheme
import id.ac.ceh.ui.HomeEventRow
import id.ac.ceh.ui.HomeScreen
import id.ac.ceh.ui.HomeUiState
import id.ac.ceh.ui.ProfileScreen
import id.ac.ceh.ui.ProfileUiState
import id.ac.ceh.ui.SavedScreen
import id.ac.ceh.ui.SavedUiState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal fun Event.category(labels: Array<String>) = categoryId?.takeIf { it in 1L until labels.size.toLong() }?.let { labels[it.toInt()] } ?: labels[0]
internal fun dateLabel(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
internal fun Event.displayDate(time: Long): String = if (source != DICODING_SOURCE) dateLabel(time) else
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply {
        timeZone = TimeZone.getTimeZone(DICODING_ZONE.id)
    }.format(Date(time)) + " WIB"
internal val Event.isDicodingOnline: Boolean
    get() = source == DICODING_SOURCE && location.equals(DICODING_ONLINE, ignoreCase = true)
class HomeFragment : Fragment() {
    private val hub by activityViewModels<HubViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            id = R.id.list
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val nav = findNavController()
        val handle = nav.getBackStackEntry(R.id.homeFragment).savedStateHandle
        val query = handle.getStateFlow("homeQuery", "")
        val category = handle.getStateFlow("homeCategory", 0)
        val city = handle.getStateFlow("homeDicodingCity", "")
        val filters = combine(query, category, city, ::Triple)
        val labels = resources.getStringArray(R.array.categories)
        val state = mutableStateOf(HomeUiState(query.value, category.value, emptyList(), true, false))
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hub.events, hub.location, hub.busy, filters) { events, location, busy, filter ->
                    val (text, index, selectedCity) = filter
                    val cities = events.filter { it.source == DICODING_SOURCE && !it.isDicodingOnline }.mapNotNull { it.location }
                        .distinctBy { it.lowercase(java.util.Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
                    val term = text.trim()
                    val rows = events.filter {
                        (index == 0 || it.categoryId == index.toLong()) &&
                            (selectedCity.isEmpty() || (it.source == DICODING_SOURCE && it.location.equals(selectedCity, ignoreCase = true))) &&
                            (it.title.contains(term, true) || it.location?.contains(term, true) == true ||
                                it.description?.contains(term, true) == true)
                    }.map { it to location?.let(it::distanceFrom) }
                        .sortedWith(compareBy<Pair<Event, Double?>> { it.second ?: Double.POSITIVE_INFINITY }
                            .thenBy { it.first.startTime })
                        .map { (event, distance) ->
                            HomeEventRow(event.id, event.title, event.category(labels), event.displayDate(event.startTime),
                                listOfNotNull(if (event.isDicodingOnline) getString(R.string.dicoding_online) else event.location, distance?.let { getString(R.string.distance_km, it) })
                                    .joinToString(" · "), event.imageUrl, event.source == DICODING_SOURCE)
                        }
                    HomeUiState(text, index, rows, busy, location != null, selectedCity, cities, events.any { it.isDicodingOnline })
                }.collect { state.value = it }
            }
        }
        (view as ComposeView).setContent {
            HubTheme {
                HomeScreen(state.value, labels.asList(),
                    onQuery = { handle["homeQuery"] = it }, onCategory = { handle["homeCategory"] = it },
                    onCity = { handle["homeDicodingCity"] = it },
                    onLocate = hub::requestLocation, onImport = hub::refreshDicoding,
                    onCreate = { nav.navigate(if (hub.user.value == null) R.id.authFragment else R.id.createFragment) },
                    onOpen = { id -> nav.navigate(R.id.eventDetailFragment, bundleOf("eventId" to id)) })
            }
        }
    }

}

class SavedFragment : Fragment() {
    private val hub by activityViewModels<HubViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            id = R.id.list
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val nav = findNavController()
        val owner = viewLifecycleOwner
        val entry = nav.getBackStackEntry(R.id.savedFragment)
        val handle = entry.savedStateHandle
        val attending = handle.getStateFlow("savedAttending", false)
        val categories = resources.getStringArray(R.array.categories)
        val state = mutableStateOf(SavedUiState(hub.user.value, attending.value, null))
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hub.personalEvents, attending) { personal, showAttending ->
                    val events = if (showAttending) personal.attending else personal.bookmarks
                    val rows = events?.sortedBy { it.startTime }?.map { event ->
                        HomeEventRow(event.id, event.title, event.category(categories), event.displayDate(event.startTime),
                            if (event.isDicodingOnline) getString(R.string.dicoding_online) else event.location.orEmpty(),
                            event.imageUrl, event.source == DICODING_SOURCE)
                    }
                    SavedUiState(personal.account, showAttending, rows, personal.failed)
                }.collect { state.value = it }
            }
        }
        fun isCurrent() = nav.currentBackStackEntry === entry && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        (view as ComposeView).setContent {
            HubTheme {
                SavedScreen(state.value,
                    onSegment = { handle["savedAttending"] = it },
                    onSignIn = { if (isCurrent() && hub.user.value == null) nav.navigate(R.id.authFragment) },
                    onBrowse = { if (isCurrent()) nav.popBackStack(R.id.homeFragment, false) },
                    onRetry = hub::reloadPersonalEvents,
                    onOpen = { id ->
                        if (isCurrent() && state.value.account != null && state.value.account == hub.user.value) {
                            nav.navigate(R.id.eventDetailFragment, bundleOf("eventId" to id))
                        }
                    },
                )
            }
        }
    }
}

class ProfileFragment : Fragment() {
    private val hub by activityViewModels<HubViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            id = R.id.list
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val nav = findNavController()
        val entry = nav.getBackStackEntry(R.id.profileFragment)
        val owner = viewLifecycleOwner
        val categories = resources.getStringArray(R.array.categories)
        fun profileState(account: String?, events: List<Event>): ProfileUiState {
            if (account == null) return ProfileUiState(null)
            val rows = events.asSequence()
                .filter { it.isOwnedBy(account) }
                .sortedBy { it.startTime }
                .map { event ->
                    HomeEventRow(event.id, event.title, event.category(categories), event.displayDate(event.startTime),
                        event.location.orEmpty(), event.imageUrl, isDicoding = false)
                }.toList()
            return ProfileUiState(account, rows)
        }
        val state = mutableStateOf(profileState(hub.user.value, hub.events.value))
        val cloudState = mutableStateOf(hub.cloudProfile.value)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { hub.cloudProfile.collect { cloudState.value = it } }
                launch { hub.user.collect { if (it?.startsWith("firebase:") == true) hub.loadCloudProfile() } }
            }
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hub.user, hub.events, ::profileState).collect { snapshot ->
                    if (snapshot.account == hub.user.value) state.value = snapshot
                }
            }
        }

        fun canAct(account: String?) = nav.currentBackStackEntry === entry &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hub.user.value == account

        (view as ComposeView).setContent {
            HubTheme {
                val snapshot = state.value
                val account = snapshot.account
                ProfileScreen(snapshot, cloudState.value.takeIf { it.account == account },
                    onReloadCloud = { if (account != null && canAct(account)) hub.loadCloudProfile() },
                    onSaveCloud = { if (account != null && canAct(account)) hub.saveCloudProfile(it) },
                    onSignIn = { if (account == null && canAct(null)) nav.navigate(R.id.authFragment) },
                    onSignOut = {
                        if (account != null && canAct(account)) {
                            hub.signOut()
                            state.value = ProfileUiState(null)
                        }
                    },
                    onCreate = { if (account != null && canAct(account)) nav.navigate(R.id.createFragment) },
                    onOpen = { id ->
                        if (account != null && canAct(account) && hub.events.value.any { it.id == id && it.isOwnedBy(account) }) {
                            nav.navigate(R.id.eventDetailFragment, bundleOf("eventId" to id))
                        }
                    },
                )
            }
        }
    }
}

class AuthFragment : Fragment() {
    private val hub by activityViewModels<HubViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val nav = findNavController()
        val entry = nav.currentBackStackEntry
        val owner = viewLifecycleOwner
        val state = mutableStateOf(hub.auth.value)
        fun isCurrent() = entry?.destination?.id == R.id.authFragment && nav.currentBackStackEntry === entry &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { hub.auth.collect { state.value = it } }
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                hub.user.collect { if (it != null && isCurrent()) nav.popBackStack() }
            }
        }
        (view as ComposeView).setContent {
            HubTheme {
                AuthScreen(state.value, hub.demoAvailable,
                    onAuthenticate = { email, password, register ->
                        if (isCurrent()) hub.authenticate(email, password, register)
                    },
                    onEnterDemo = { if (isCurrent()) hub.enterDemo() },
                )
            }
        }
    }
}
