package id.ac.ceh.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users", indices = [Index(value = ["email"], unique = true)])
internal data class User(
    @PrimaryKey val id: String,
    val name: String,
    val email: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
internal data class Category(@PrimaryKey val id: Long, val name: String, val icon: String? = null)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(entity = User::class, parentColumns = ["id"], childColumns = ["user_id"]),
        ForeignKey(entity = Event::class, parentColumns = ["id"], childColumns = ["event_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["user_id", "event_id"], unique = true), Index("event_id")]
)
internal data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "saved_at") val savedAt: Long
)

@Entity(
    tableName = "rsvps",
    foreignKeys = [
        ForeignKey(entity = User::class, parentColumns = ["id"], childColumns = ["user_id"]),
        ForeignKey(entity = Event::class, parentColumns = ["id"], childColumns = ["event_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["user_id", "event_id"], unique = true), Index("event_id")]
)
internal data class Rsvp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "event_id") val eventId: Long,
    val status: String,
    @ColumnInfo(name = "remind_at") val remindAt: Long? = null
)

@Dao
internal interface EventDao {
    @Query("SELECT * FROM events ORDER BY start_time, id")
    fun events(): Flow<List<Event>>

    @Query("SELECT e.* FROM events e JOIN bookmarks b ON b.event_id = e.id WHERE b.user_id = :user ORDER BY e.start_time, e.id")
    fun saved(user: String): Flow<List<Event>>

    @Query("SELECT e.* FROM events e JOIN rsvps r ON r.event_id = e.id WHERE r.user_id = :user ORDER BY e.start_time, e.id")
    fun rsvps(user: String): Flow<List<Event>>

    @Query("SELECT e.* FROM events e JOIN rsvps r ON r.event_id = e.id WHERE r.user_id = :user AND r.status = 'going' ORDER BY e.start_time, e.id")
    fun attending(user: String): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun find(id: Long): Event?

    @Query("SELECT * FROM events WHERE external_id = :externalId")
    suspend fun findExternal(externalId: String): Event?

    @Insert suspend fun insert(event: Event): Long
    @Update suspend fun update(event: Event)
    @Delete suspend fun delete(event: Event)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUser(user: User)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<Category>)

    @Insert suspend fun insertBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE user_id = :user AND event_id = :event")
    suspend fun removeBookmark(user: String, event: Long): Int

    @Insert suspend fun insertRsvp(rsvp: Rsvp)

    @Query("DELETE FROM rsvps WHERE user_id = :user AND event_id = :event")
    suspend fun removeRsvp(user: String, event: Long)

    @Query("UPDATE rsvps SET status = :status WHERE user_id = :user AND event_id = :event")
    suspend fun updateRsvp(user: String, event: Long, status: String): Int
}

internal val defaultCategories = listOf(
    Category(1, "Musik"), Category(2, "Olahraga"), Category(3, "Workshop"),
    Category(4, "Sosial"), Category(5, "Seni"), Category(6, "Webinar dan Seminar"),
    Category(7, "Kelas dan Belajar"), Category(8, "Komunitas Teknologi")
)

@Database(entities = [User::class, Category::class, Event::class, Bookmark::class, Rsvp::class], version = 1, exportSchema = false)
internal abstract class EventDatabase : RoomDatabase() {
    abstract fun dao(): EventDao

    companion object {
        @Volatile private var instance: EventDatabase? = null

        fun get(context: Context): EventDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, EventDatabase::class.java, "ceh.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (category in defaultCategories) {
                            db.execSQL("INSERT INTO categories (id, name) VALUES (?, ?)", arrayOf<Any>(category.id, category.name))
                        }
                    }
                })
                .build().also { instance = it }
        }
    }
}
