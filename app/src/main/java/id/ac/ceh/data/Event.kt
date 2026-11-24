package id.ac.ceh.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["category_id"]),
        ForeignKey(entity = User::class, parentColumns = ["id"], childColumns = ["organizer_id"])
    ],
    indices = [Index("category_id"), Index("organizer_id"), Index(value = ["external_id"], unique = true)]
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "organizer_id") val organizerId: String? = null,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "end_time") val endTime: Long? = null,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    val source: String = "user",
    @ColumnInfo(name = "external_id") val externalId: String? = null
)
