package id.ac.ceh

import id.ac.ceh.data.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceTest {
    @Test fun samePoint() = assertEquals(0.0, distanceKm(-6.2, 106.8, -6.2, 106.8), 0.001)
    @Test fun equatorialDegree() = assertEquals(111.195, distanceKm(0.0, 0.0, 0.0, 1.0), 0.001)
    @Test fun crossesDateLine() = assertEquals(22.239, distanceKm(0.0, 179.9, 0.0, -179.9), 0.001)

    @Test
    fun validPointPreservesCoordinatesAndAcceptsBounds() {
        assertEquals(EventPoint(-6.18, 106.83), validPoint(-6.18, 106.83))
        assertEquals(EventPoint(-90.0, -180.0), validPoint(-90.0, -180.0))
        assertEquals(EventPoint(90.0, 180.0), validPoint(90.0, 180.0))
    }

    @Test
    fun validPointRejectsOutOfRangeCoordinates() {
        assertNull(validPoint(-90.01, 106.83))
        assertNull(validPoint(90.01, 106.83))
        assertNull(validPoint(-6.18, -180.01))
        assertNull(validPoint(-6.18, 180.01))
    }

    @Test
    fun validPointRejectsNonFiniteCoordinates() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull("Latitude: $value", validPoint(value, 106.83))
            assertNull("Longitude: $value", validPoint(-6.18, value))
        }
    }

    @Test
    fun mapPointAcceptsOnlyUserSource() {
        assertEquals(EventPoint(-6.18, 106.83), localEvent.mapPoint())
        assertNull(localEvent.copy(source = "dicoding").mapPoint())
    }

    @Test
    fun mapPointRejectsOnlineAndDaringLabels() {
        for (label in listOf("Online", "Daring", " oNLiNe ", " dARing ")) {
            assertNull("Location: '$label'", localEvent.copy(location = label).mapPoint())
        }
    }

    @Test
    fun mapPointRejectsMissingOrInvalidCoordinates() {
        assertNull(localEvent.copy(latitude = null).mapPoint())
        assertNull(localEvent.copy(longitude = null).mapPoint())
        assertNull(localEvent.copy(latitude = 91.0).mapPoint())
        assertNull(localEvent.copy(longitude = 181.0).mapPoint())
    }

    @Test
    fun haversineRanksNearbyJakartaPointBeforeBandung() {
        val nearbyKm = distanceKm(-6.18, 106.83, -6.20, 106.85)
        val distantKm = distanceKm(-6.18, 106.83, -6.9175, 107.6191)

        assertEquals(3.14, nearbyKm, 0.05)
        assertEquals(119.6, distantKm, 1.0)
        assertTrue(nearbyKm < distantKm)
    }

    private val localEvent = Event(
        title = "Acara uji",
        startTime = 0L,
        location = "Jakarta",
        latitude = -6.18,
        longitude = 106.83,
        source = "user",
    )
}
