package id.ac.ceh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventDraftTest {
    private val valid = EventDraft(title = "  Acara uji  ", start = 1_000)

    @Test fun requiresTitleAndStart() {
        assertEquals(DraftIssue.Title, valid.copy(title = " \t").issue())
        assertEquals(DraftIssue.Start, valid.copy(start = null).issue())
        assertNull(valid.copy(start = 0).toEvent(0))
    }

    @Test fun endMustBeStrictlyAfterStart() {
        assertEquals(DraftIssue.End, valid.copy(end = 1_000).issue())
        assertEquals(DraftIssue.End, valid.copy(end = 999).issue())
        assertNull(valid.copy(end = 1_001).issue())
    }

    @Test fun coordinatesMustBePairedAndInRange() {
        assertEquals(DraftIssue.Coordinates, valid.copy(latitude = -6.0).issue())
        assertEquals(DraftIssue.Coordinates, valid.copy(latitude = 91.0, longitude = 106.0).issue())
        assertEquals(DraftIssue.Coordinates, valid.copy(latitude = Double.NaN, longitude = 106.0).issue())
        assertNull(valid.copy(latitude = -6.0, longitude = 106.0).issue())
    }

    @Test fun imageRequiresHttpsHostWithoutCredentialsIfProvided() {
        listOf("http://example.org/banner.png", "https:///banner.png", "https://user:pass@example.org", "https://two words.org").forEach {
            assertEquals(DraftIssue.Image, valid.copy(imageUrl = it).issue())
        }
        assertNull(valid.copy(imageUrl = " HTTPS://example.org/banner.png ").issue())
        assertNull(checkNotNull(valid.copy(imageUrl = "  ").toEvent(0)).imageUrl)
    }

    @Test fun validEditKeepsIdAndNormalizesFields() {
        val event = checkNotNull(valid.copy(place = " Jakarta ", category = 3).toEvent(22))
        assertEquals(22L, event.id)
        assertEquals("Acara uji", event.title)
        assertEquals("Jakarta", event.location)
        assertEquals(3L, event.categoryId)
        assertEquals("user", event.source)
        assertNull(event.externalId)
    }
}
