package id.ac.ceh

import android.location.Location
import id.ac.ceh.data.Event
import kotlin.math.*

internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val a = sin(Math.toRadians(lat2 - lat1) / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
    return 6371.0088 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

internal data class EventPoint(val latitude: Double, val longitude: Double)

internal fun validPoint(latitude: Double, longitude: Double): EventPoint? =
    if (latitude in -90.0..90.0 && longitude in -180.0..180.0) EventPoint(latitude, longitude) else null

internal fun Event.mapPoint(): EventPoint? {
    if (source != "user" || location?.trim()?.let { it.equals("Daring", true) || it.equals("Online", true) } == true) return null
    return validPoint(latitude ?: return null, longitude ?: return null)
}

internal fun Event.distanceFrom(origin: Location): Double? = mapPoint()?.let {
    distanceKm(origin.latitude, origin.longitude, it.latitude, it.longitude)
}
