package id.ac.ceh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class ForegroundLocation(private val context: Context) {
    enum class Access { Precise, Approximate }

    sealed interface Result {
        data class Fix(val location: Location) : Result
        data object Denied : Result
        data object Disabled : Result
        data object Unavailable : Result
    }

    private val executor = ContextCompat.getMainExecutor(context)

    fun access(): Access? = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Access.Precise
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> Access.Approximate
        else -> null
    }

    suspend fun current(): Result {
        val access = access() ?: return Result.Denied
        val manager = context.getSystemService(LocationManager::class.java) ?: return Result.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return Result.Disabled

        val fix = withTimeoutOrNull(FUSED_TIMEOUT_MS) { fusedFix(access)?.takeIf { it.isUsable() } }
            ?: withTimeoutOrNull(FALLBACK_TIMEOUT_MS) { platformFix(manager, access) }

        val currentAccess = access()
        return when {
            currentAccess == null -> Result.Denied
            !LocationManagerCompat.isLocationEnabled(manager) -> Result.Disabled
            currentAccess != access || fix == null -> Result.Unavailable
            else -> Result.Fix(fix)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedFix(access: Access): Location? = try {
        if (GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            null
        } else {
            val request = CurrentLocationRequest.Builder()
                .setPriority(if (access == Access.Precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setMaxUpdateAgeMillis(0L)
                .setDurationMillis(FUSED_TIMEOUT_MS)
                .build()
            suspendCancellableCoroutine { continuation ->
                val token = CancellationTokenSource()
                continuation.invokeOnCancellation { token.cancel() }
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, token.token)
                    .addOnCompleteListener(executor) { task ->
                        continuation.resume(if (task.isSuccessful) task.result else null)
                    }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private suspend fun platformFix(manager: LocationManager, access: Access): Location? = coroutineScope {
        val providers = buildList {
            if (access == Access.Precise) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) return@coroutineScope null

        val results = Channel<Location?>(providers.size)
        val requests = providers.map { provider ->
            launch { results.send(providerFix(manager, provider)) }
        }
        try {
            repeat(providers.size) {
                val fix = results.receive()
                if (fix != null && fix.isUsable()) return@coroutineScope fix
            }
            null
        } finally {
            requests.forEach { it.cancel() }
            results.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun providerFix(manager: LocationManager, provider: String): Location? = try {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { fix ->
                continuation.resume(fix)
            }
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.isUsable(): Boolean {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        return elapsedRealtimeNanos > 0 && ageNanos in 0L..MAX_FIX_AGE_NANOS &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    private companion object {
        const val FUSED_TIMEOUT_MS = 8_000L
        const val FALLBACK_TIMEOUT_MS = 7_000L
        const val MAX_FIX_AGE_NANOS = 30_000_000_000L
    }
}
