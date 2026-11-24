package id.ac.ceh

import id.ac.ceh.data.Event
import java.net.URI
import java.net.URISyntaxException

internal data class EventDraft(
    val title: String = "",
    val description: String = "",
    val category: Int = 0,
    val start: Long? = null,
    val end: Long? = null,
    val place: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val imageUrl: String = "",
) {
    fun issue(): DraftIssue? = when {
        title.isBlank() -> DraftIssue.Title
        start == null || start <= 0 -> DraftIssue.Start
        end != null && end <= start -> DraftIssue.End
        (latitude != null || longitude != null) && point() == null -> DraftIssue.Coordinates
        !imageUrl.isBlank() && !isHttpsImage(imageUrl.trim()) -> DraftIssue.Image
        else -> null
    }

    fun point(): EventPoint? = if (latitude != null && longitude != null) validPoint(latitude, longitude) else null

    fun toEvent(id: Long): Event? {
        if (issue() != null) return null
        return Event(id = id, title = title.trim(), description = description.trim().ifEmpty { null },
            categoryId = category.toLong().takeIf { it > 0 }, startTime = checkNotNull(start), endTime = end,
            location = place.trim().ifEmpty { null }, latitude = latitude, longitude = longitude,
            imageUrl = imageUrl.trim().ifEmpty { null }, source = "user")
    }
}

internal enum class DraftIssue { Title, Start, End, Coordinates, Image }

private fun isHttpsImage(text: String): Boolean = try {
    val uri = URI(text)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
} catch (_: URISyntaxException) {
    false
}

internal fun Event.toDraft() = EventDraft(title, description.orEmpty(), categoryId?.toInt() ?: 0,
    startTime, endTime, location.orEmpty(), latitude, longitude, imageUrl.orEmpty())
