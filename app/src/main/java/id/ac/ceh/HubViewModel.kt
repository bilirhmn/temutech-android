package id.ac.ceh

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import id.ac.ceh.data.CloudAccountGateway
import id.ac.ceh.data.Event
import id.ac.ceh.ui.AuthUiState
import id.ac.ceh.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HubViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val demoAvailable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val prefs = app.getSharedPreferences("hub_session", Context.MODE_PRIVATE)
    private val cloud = CloudAccountGateway.create(app)
    private fun activeAccount(): String? = cloud?.uid?.let { "firebase:$it" }
        ?: "demo".takeIf { demoAvailable && prefs.getBoolean("demo", false) }
    private val _user = MutableStateFlow(activeAccount())
    val user = _user.asStateFlow()
    private val _auth = MutableStateFlow(AuthUiState(available = cloud != null))
    internal val auth = _auth.asStateFlow()
    private val _cloudProfile = MutableStateFlow(CloudProfileState())
    internal val cloudProfile = _cloudProfile.asStateFlow()
    private var profileJob: Job? = null
    private val notices = Channel<Int>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()
    private fun <T> Flow<T>.reportErrors() = catch { notices.trySend(R.string.operation_failed) }
    private val _catalogReady = MutableStateFlow(false)
    internal val catalogReady = _catalogReady.asStateFlow()
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events = _events.asStateFlow()

    init {
        if (cloud != null) viewModelScope.launch {
            cloud.sessions.collect { syncAccount() }
        }
        viewModelScope.launch {
            repo.events.reportErrors().collect {
                _events.value = it
                _catalogReady.value = true
            }
            _catalogReady.value = true
        }
    }
    private val personalRetry = MutableStateFlow(0)
    private val personal = combine(user, personalRetry) { account, _ -> account }.flatMapLatest { account ->
        if (account == null) flowOf(PersonalEvents(null)) else {
            combine(repo.saved(account), repo.attending(account)) { bookmarks, attending ->
                PersonalEvents(account, bookmarks, attending)
            }.onStart { emit(PersonalEvents(account)) }
                .catch { emit(PersonalEvents(account, failed = true)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), PersonalEvents(null))

    internal val personalEvents = combine(user, personal) { account, snapshot ->
        if (account != null && account == snapshot.account) snapshot else PersonalEvents(account)
    }
    internal fun reloadPersonalEvents() { personalRetry.update { it + 1 } }

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private fun syncAccount() {
        val next = activeAccount()
        if (_user.value == next) return
        profileJob?.cancel()
        profileJob = null
        _cloudProfile.value = CloudProfileState()
        _user.value = next
    }

    fun enterDemo() {
        if (!demoAvailable || user.value != null || auth.value.busy) return
        prefs.edit().putBoolean("demo", true).apply()
        syncAccount()
    }

    fun signOut() {
        if (auth.value.busy) return
        prefs.edit().remove("demo").apply()
        cloud?.signOut()
        _auth.update { it.copy(error = null) }
        syncAccount()
    }

    internal fun authenticate(email: String, password: String, register: Boolean) {
        val gateway = cloud ?: return
        if (auth.value.busy || user.value != null) return
        val address = email.trim()
        val error = when {
            address.length > 254 || !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches() -> R.string.cloud_auth_invalid_email
            password.isEmpty() -> R.string.cloud_auth_short_password
            register && password.length < 6 -> R.string.cloud_auth_short_password
            else -> null
        }
        if (error != null) {
            _auth.update { it.copy(error = error) }
            return
        }
        _auth.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                gateway.authenticate(address, password, register)
                syncAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _auth.update { it.copy(error = cloudAuthError(e)) }
            } finally {
                _auth.update { it.copy(busy = false) }
            }
        }
    }

    internal fun loadCloudProfile() = updateCloudProfile { gateway, uid -> gateway.readDisplayName(uid) }

    internal fun saveCloudProfile(name: String) {
        val displayName = name.trim()
        if (displayName.codePointCount(0, displayName.length) !in 1..80) return
        updateCloudProfile { gateway, uid ->
            gateway.writeDisplayName(uid, displayName)
            displayName
        }
    }

    private fun updateCloudProfile(action: suspend (CloudAccountGateway, String) -> String?) {
        val gateway = cloud ?: return
        val uid = gateway.uid ?: return
        val account = "firebase:$uid"
        if (user.value != account || profileJob?.isActive == true) return
        val previous = cloudProfile.value.takeIf { it.account == account }?.name
        _cloudProfile.value = CloudProfileState(account, previous, busy = true)
        profileJob = viewModelScope.launch {
            try {
                val name = action(gateway, uid)
                if (user.value == account) _cloudProfile.value = CloudProfileState(account, name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (user.value == account) _cloudProfile.value = CloudProfileState(account, previous, failed = true)
            }
        }
    }
    fun refreshDicoding() = runTask(R.string.dicoding_import_failed) {
        val imported = repo.refreshDicoding()
        notices.trySend(if (imported == 0) R.string.dicoding_import_empty else R.string.dicoding_import_done)
    }
    fun bookmark(id: Long) = withUser { repo.bookmark(it, id) }
    fun attend(id: Long, going: Boolean) = withUser { repo.rsvp(it, id, if (going) "going" else "cancelled") }
    private val _savedEditor = MutableStateFlow<String?>(null)
    internal val savedEditor = _savedEditor.asStateFlow()

    internal fun saveEditor(token: String, event: Event) {
        if (busy.value || savedEditor.value == token) return
        viewModelScope.launch {
            change(event.id) { account ->
                repo.save(account, event.copy(organizerId = account))
                _savedEditor.value = token
            }
        }
    }
    suspend fun delete(id: Long): Boolean = change(id) { repo.delete(it, id) }

    private suspend fun change(id: Long, action: suspend (String) -> Unit): Boolean {
        val account = user.value ?: return false
        if (id != 0L && events.value.none { it.id == id && it.isOwnedBy(account) }) return false
        if (_busy.value) return false
        _busy.value = true
        return try {
            action(account)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notices.trySend(R.string.operation_failed)
            false
        } finally {
            _busy.value = false
        }
    }
    private fun withUser(action: suspend (String) -> Unit) {
        val id = user.value ?: return
        runTask { if (user.value == id) action(id) }
    }
    private fun runTask(errorMessage: Int = R.string.operation_failed, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notices.trySend(errorMessage) }
            finally { _busy.value = false }
        }
    }

    private val foregroundLocation = ForegroundLocation(app)
    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()
    private val _permissionNeeded = MutableStateFlow(false)
    internal val permissionNeeded = _permissionNeeded.asStateFlow()
    private var locationJob: Job? = null
    private var locationResumed = false
    private var permissionInFlight = false
    private var permissionDenied = false
    private var locateAfterResume = false

    internal fun onLocationResumed() {
        locationResumed = true
        if (locateAfterResume) {
            locateAfterResume = false
            startLocation()
        }
    }

    internal fun onLocationPaused() {
        locationResumed = false
        _permissionNeeded.value = false
        locationJob?.cancel()
        locationJob = null
        _location.value = null
    }

    internal fun onLocationStopped() {
        permissionInFlight = false
        locateAfterResume = false
    }

    internal fun beginLocationPermissionPrompt(): Boolean {
        if (!locationResumed || !_permissionNeeded.value || permissionInFlight) return false
        _permissionNeeded.value = false
        if (foregroundLocation.access() != null) {
            startLocation()
            return false
        }
        permissionInFlight = true
        return true
    }

    internal fun onLocationPermissionResult() {
        val hasAccess = foregroundLocation.access() != null
        if (!hasAccess) permissionDenied = true
        if (!permissionInFlight) return
        permissionInFlight = false
        if (!hasAccess) {
            notices.trySend(R.string.location_denied)
        } else if (locationResumed) {
            startLocation()
        } else {
            // The permission result can arrive before the Activity resumes.
            locateAfterResume = true
        }
    }

    fun requestLocation() {
        if (!locationResumed || permissionInFlight) return
        if (foregroundLocation.access() != null) {
            startLocation()
        } else if (permissionDenied) {
            notices.trySend(R.string.location_denied)
        } else {
            _permissionNeeded.value = true
        }
    }

    private fun startLocation() {
        _permissionNeeded.value = false
        locationJob?.cancel()
        _location.value = null
        locationJob = viewModelScope.launch {
            val result = foregroundLocation.current()
            ensureActive()
            when (result) {
                is ForegroundLocation.Result.Fix -> _location.value = result.location
                ForegroundLocation.Result.Denied -> notices.trySend(R.string.location_denied)
                ForegroundLocation.Result.Disabled -> notices.trySend(R.string.location_disabled)
                ForegroundLocation.Result.Unavailable -> notices.trySend(R.string.location_unavailable)
            }
        }
    }
}

private fun cloudAuthError(error: Exception): Int = when (error) {
    is FirebaseNetworkException -> R.string.cloud_auth_network
    is FirebaseTooManyRequestsException -> R.string.cloud_auth_rate_limit
    is FirebaseAuthException -> when (error.errorCode) {
        "ERROR_INVALID_EMAIL" -> R.string.cloud_auth_invalid_email
        "ERROR_EMAIL_ALREADY_IN_USE" -> R.string.cloud_auth_email_in_use
        "ERROR_WEAK_PASSWORD" -> R.string.cloud_auth_weak_password
        "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED" -> R.string.cloud_auth_invalid_credentials
        "ERROR_OPERATION_NOT_ALLOWED", "ERROR_APP_NOT_AUTHORIZED" -> R.string.cloud_auth_unavailable
        else -> R.string.cloud_auth_failed
    }
    else -> R.string.cloud_auth_failed
}

internal data class CloudProfileState(val account: String? = null, val name: String? = null,
    val busy: Boolean = false, val failed: Boolean = false)

internal data class PersonalEvents(
    val account: String?,
    val bookmarks: List<Event>? = null,
    val attending: List<Event>? = null,
    val failed: Boolean = false,
)

internal fun Event.isOwnedBy(account: String?) = account != null && organizerId == account && source == "user"
