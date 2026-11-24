package id.ac.ceh.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: EventDatabase
    private lateinit var repo: Repository
    private var networkError: Exception? = null

    @Before fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        repo = Repository(db, object : DicodingApi {
            override suspend fun events(): DicodingResponse {
                networkError?.let { throw it }
                throw AssertionError("Local operations must not call the API")
            }
        })
    }

    @After fun closeDatabase() = db.close()

    @Test fun ownershipValidationToggleAndCascade() = runBlocking {
        val draft = Event(title = "  Community concert  ", categoryId = 1, startTime = 1000)
        expectInvalid { repo.save("owner", draft.copy(endTime = 999)) }
        repo.save("owner", draft.copy(organizerId = "spoofed"))
        val event = repo.events.first().single()
        assertEquals("owner", event.organizerId)
        assertEquals("Community concert", event.title)
        expectInvalid { repo.save("other", event.copy(organizerId = "other")) }
        expectInvalid { repo.delete("other", event.id) }
        assertEquals(event, repo.events.first().single())
        assertCounts("Aksi nonpemilik", 1, 0, 0)
        repo.save("owner", event.copy(title = "New title"))
        assertEquals("New title", repo.events.first().single().title)
        repo.bookmark("other", event.id)
        assertEquals(event.id, repo.saved("other").first().single().id)
        assertTrue(repo.saved("owner").first().isEmpty())
        repo.bookmark("other", event.id)
        assertTrue(repo.saved("other").first().isEmpty())
        repo.bookmark("other", event.id)
        expectInvalid { repo.rsvp("other", event.id, "maybe") }
        repo.rsvp("other", event.id, "going")
        repo.rsvp("other", event.id, "interested")
        assertCounts("Perubahan status RSVP", 1, 1, 1)
        assertEquals(event.id, repo.rsvps("other").first().single().id)
        repo.rsvp("other", event.id, "cancelled")
        assertCounts("Batal RSVP", 1, 1, 0)
        assertTrue(repo.rsvps("other").first().isEmpty())
        repo.rsvp("other", event.id, "going")
        assertEquals(event.id, repo.attending("other").first().single().id)
        assertCounts("Sebelum hapus pemilik", 1, 1, 1)
        repo.delete("owner", event.id)
        assertCounts("Sesudah hapus pemilik", 0, 0, 0)
        assertTrue(repo.events.first().isEmpty())
        assertTrue(repo.saved("other").first().isEmpty())
        assertTrue(repo.rsvps("other").first().isEmpty())
        assertTrue(repo.attending("other").first().isEmpty())
    }

    @Test fun attendingIncludesOnlyGoingForTheRequestedUser() = runBlocking {
        repo.save("owner", Event(title = "Acara A", startTime = 1000))
        repo.save("owner", Event(title = "Acara B", startTime = 2000))
        repo.save("owner", Event(title = "Acara C", startTime = 3000))
        val (eventA, eventB, eventC) = repo.events.first()

        repo.bookmark("member", eventA.id)
        repo.rsvp("member", eventB.id, "going")
        repo.rsvp("member", eventC.id, "interested")
        repo.rsvp("owner", eventA.id, "going")

        assertEquals(listOf(eventA), repo.saved("member").first())
        assertEquals(listOf(eventB), repo.attending("member").first())
        assertEquals(listOf(eventB, eventC), repo.rsvps("member").first())
        assertEquals(listOf(eventA), repo.attending("owner").first())

        repo.rsvp("member", eventB.id, "cancelled")

        assertTrue(repo.attending("member").first().isEmpty())
        assertEquals(listOf(eventA), repo.saved("member").first())
        assertEquals(listOf(eventC), repo.rsvps("member").first())
        assertEquals(listOf(eventA), repo.attending("owner").first())
    }

    @Test fun savedAndRsvpSurviveReopeningFileWithoutRemote() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "room-test-${UUID.randomUUID()}.db"
        val noRemote = object : DicodingApi {
            override suspend fun events(): DicodingResponse =
                throw AssertionError("Local database reads must not contact the API")
        }
        suspend fun <T> withFileDatabase(block: suspend (EventDatabase) -> T): T {
            val fileDb = Room.databaseBuilder(context, EventDatabase::class.java, name).build()
            return try { block(fileDb) } finally { fileDb.close() }
        }
        try {
            val eventId = withFileDatabase { fileDb ->
                val fileRepo = Repository(fileDb, noRemote)
                fileRepo.save("owner", Event(title = "Persistent fixture", startTime = 1000))
                val id = fileRepo.events.first().single().id
                fileRepo.bookmark("owner", id)
                fileRepo.rsvp("owner", id, "going")
                assertCounts("Sebelum tutup file uji", 1, 1, 1, fileDb)
                id
            }
            withFileDatabase { fileDb ->
                val fileRepo = Repository(fileDb, noRemote)
                assertCounts("Sesudah buka ulang file uji", 1, 1, 1, fileDb)
                assertEquals(eventId, fileRepo.events.first().single().id)
                assertEquals(eventId, fileRepo.saved("owner").first().single().id)
                assertEquals(eventId, fileRepo.rsvps("owner").first().single().id)
                assertEquals(eventId, fileRepo.attending("owner").first().single().id)
            }
        } finally {
            withContext(Dispatchers.IO) { context.deleteDatabase(name) }
        }
    }

    @Test fun dicodingUpsertKeepsLocalIdAndCacheOnFailure() = runBlocking {
        val fixture = DicodingEvent(19, "Seminar awal", null, null, null, null, "Seminar", null,
            "Bandung", null, null, "2026-12-01T19:00:00+07:00", null, null, null)
        var page = DicodingResponse(false, listOf(fixture, fixture))
        val external = object : DicodingApi {
            override suspend fun events() = page
        }
        val dicoding = Repository(db, external)
        assertEquals(1, dicoding.refreshDicoding())
        val first = dicoding.events.first().single()
        assertEquals("dicoding:19", first.externalId)
        expectInvalid { dicoding.delete("owner", first.id) }
        expectInvalid { dicoding.save("owner", first.copy(source = "user", externalId = null)) }
        assertEquals(first, dicoding.events.first().single())
        dicoding.bookmark("owner", first.id)
        dicoding.rsvp("owner", first.id, "going")
        page = DicodingResponse(false, listOf(fixture.copy(name = "Seminar baru")))
        assertEquals(1, dicoding.refreshDicoding())
        val updated = dicoding.events.first().single()
        assertEquals(first.id, updated.id)
        assertEquals("Seminar baru", updated.title)
        assertEquals(updated, dicoding.saved("owner").first().single())
        assertEquals(updated, dicoding.rsvps("owner").first().single())
        page = DicodingResponse(false, emptyList())
        assertEquals(0, dicoding.refreshDicoding())
        page = DicodingResponse(true, null)
        assertRefreshFailure("tidak mengembalikan") { dicoding.refreshDicoding() }
        assertCounts("Cache Dicoding setelah kosong dan gagal", 1, 1, 1)
        assertEquals(updated, dicoding.saved("owner").first().single())
        assertEquals(updated, dicoding.rsvps("owner").first().single())
        assertEquals(updated, dicoding.events.first().single())
    }

    @Test fun dicodingScheduleHonorsOffsetOrDefaultsToJakarta() {
        val fixture = DicodingEvent(19, "Seminar uji zona", null, null, null, null, "Seminar", null,
            "Bandung", null, null, "2026-12-01T19:00:00+08:00", "2026-12-01T21:00:00+07:00", null, "Asia/Jakarta")

        val explicit = checkNotNull(fixture.toEvent())
        assertEquals(1796122800000L, explicit.startTime) // 11:00 UTC, not 12:00.
        assertEquals(1796133600000L, explicit.endTime)

        val local = checkNotNull(fixture.copy(beginTime = "2026-12-01 19:00:00",
            endTime = "2026-12-01 21:00:00", timezone = "Asia/Makassar").toEvent())
        assertEquals(1796126400000L, local.startTime) // 12:00 UTC, Jakarta (UTC+07:00).
        assertEquals(1796133600000L, local.endTime)
    }

    @Test fun refreshPreservesCancellation() = runBlocking {
        val cancelled = CancellationException("fixture cancelled")
        networkError = cancelled
        try {
            repo.refreshDicoding()
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
        assertCounts("Batal impor", 0, 0, 0)
    }

    private suspend fun assertRefreshFailure(message: String, action: suspend () -> Int) {
        try {
            action()
            fail("Expected refresh rejection")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains(message))
        }
    }

    private suspend fun assertCounts(stage: String, events: Int, bookmarks: Int, rsvps: Int, database: EventDatabase = db) {
        val actual = withContext(Dispatchers.IO) {
            database.openHelper.readableDatabase.query(
                "SELECT (SELECT COUNT(*) FROM events), (SELECT COUNT(*) FROM bookmarks), (SELECT COUNT(*) FROM rsvps)"
            ).use { cursor ->
                check(cursor.moveToFirst())
                List(3) { cursor.getInt(it) }
            }
        }
        Log.i("CEH-Room-F05", "$stage: events=${actual[0]} bookmarks=${actual[1]} rsvps=${actual[2]}")
        assertEquals(stage, listOf(events, bookmarks, rsvps), actual)
    }

    private suspend fun expectInvalid(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected invalid or unauthorized operation")
        } catch (_: IllegalArgumentException) {
            // Expected validation or ownership failure.
        }
    }
}
