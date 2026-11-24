package id.ac.ceh.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import id.ac.ceh.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

import kotlin.coroutines.coroutineContext

class Repository internal constructor(
    private val database: EventDatabase,
    private val dicoding: DicodingApi,
) {
    constructor(context: Context) : this(
        EventDatabase.get(context),
        dicodingApi(onHttpStatus = if (BuildConfig.DEBUG) { code -> Log.i("CEH-Dicoding", "HTTP status=$code"); Unit } else null),
    )

    private val dao = database.dao()
    val events: Flow<List<Event>> = dao.events()

    fun saved(user: String): Flow<List<Event>> = dao.saved(user)
    fun rsvps(user: String): Flow<List<Event>> = dao.rsvps(user)
    fun attending(user: String): Flow<List<Event>> = dao.attending(user)

    internal suspend fun refreshDicoding(): Int {
        val raw = dicoding.events().let { response ->
            check(response.error == false) { "Dicoding tidak mengembalikan hasil yang sukses." }
            checkNotNull(response.listEvents) { "Format daftar acara Dicoding tidak valid." }
        }
        val incoming = raw.mapNotNull { it?.toEvent() }.associateBy { checkNotNull(it.externalId) }
        check(raw.isEmpty() || incoming.isNotEmpty()) { "Tidak ada acara Dicoding dengan ID, judul, dan waktu valid." }
        val stored = cacheImported(incoming)
        if (BuildConfig.DEBUG) Log.i("CEH-Dicoding", "received=${raw.size} stored=$stored")
        return stored
    }

    private suspend fun cacheImported(incoming: Map<String, Event>): Int {
        if (incoming.isEmpty()) return 0
        try {
            database.withTransaction {
                dao.insertCategories(defaultCategories)
                for ((externalId, event) in incoming) {
                    val existing = dao.findExternal(externalId)
                    if (existing == null) dao.insert(event) else dao.update(event.copy(id = existing.id))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            throw IllegalStateException("Acara gagal disimpan ke penyimpanan lokal.", e)
        }
        return incoming.size
    }

    suspend fun bookmark(user: String, event: Long) {
        database.withTransaction {
            seedUser(user)
            requireEvent(event)
            if (dao.removeBookmark(user, event) == 0) {
                dao.insertBookmark(Bookmark(userId = user, eventId = event, savedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun rsvp(user: String, event: Long, status: String) {
        require(status == "going" || status == "interested" || status == "cancelled") {
            "Status RSVP harus going, interested, atau cancelled."
        }
        database.withTransaction {
            seedUser(user)
            requireEvent(event)
            if (status == "cancelled") {
                dao.removeRsvp(user, event)
            } else if (dao.updateRsvp(user, event, status) == 0) {
                dao.insertRsvp(Rsvp(userId = user, eventId = event, status = status))
            }
        }
    }

    suspend fun save(user: String, event: Event) {
        validate(event)
        database.withTransaction {
            seedUser(user)
            val owned = event.copy(title = event.title.trim(), organizerId = user)
            if (event.id == 0L) {
                dao.insertCategories(defaultCategories)
                dao.insert(owned)
            } else {
                requireOwner(user, requireEvent(event.id))
                dao.update(owned)
            }
        }
    }

    suspend fun delete(user: String, id: Long) {
        checkUser(user)
        database.withTransaction {
            val event = requireEvent(id)
            requireOwner(user, event)
            dao.delete(event)
        }
    }

    private suspend fun requireEvent(id: Long): Event =
        requireNotNull(dao.find(id)) { "Acara tidak ditemukan." }

    private fun requireOwner(user: String, event: Event) {
        require(event.source == "user" && event.organizerId == user) { "Hanya penyelenggara yang boleh mengubah atau menghapus acara ini." }
    }

    private fun checkUser(user: String) {
        require(user.isNotBlank() && user == user.trim()) { "ID pengguna tidak valid." }
        check(user != "demo" || BuildConfig.DEBUG) { "Pengguna demo hanya tersedia pada build debug." }
    }

    private suspend fun seedUser(user: String) {
        checkUser(user)
        dao.insertUser(User(user, if (user == "demo") "Demo" else user, "$user@local.invalid", System.currentTimeMillis()))
    }

    private fun validate(event: Event) {
        require(event.id >= 0) { "ID acara tidak valid." }
        require(event.source == "user" && event.externalId == null) { "Acara dari API tidak dapat disimpan sebagai acara pengguna." }
        require(event.title.isNotBlank()) { "Judul acara wajib diisi." }
        require(event.startTime > 0) { "Waktu mulai acara tidak valid." }
        require(event.endTime == null || event.endTime >= event.startTime) { "Waktu selesai tidak boleh sebelum waktu mulai." }
        require(event.categoryId == null || defaultCategories.any { it.id == event.categoryId }) { "Kategori acara tidak valid." }
        val lat = event.latitude
        val lon = event.longitude
        require((lat == null && lon == null) || (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0)) {
            "Lintang dan bujur harus diisi bersama dengan nilai yang valid."
        }
    }
}
