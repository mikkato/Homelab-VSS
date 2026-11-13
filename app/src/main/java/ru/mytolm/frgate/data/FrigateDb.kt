package ru.mytolm.frgate.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val camera: String,
    val label: String,
    val subLabel: String?,
    val since: Double,
    val endTime: Double?,
    val duration: Double?,
    val hasClip: Boolean,
    val hasSnapshot: Boolean,
    val zones: String,          // csv
    val confidence: Int,
    val retained: Boolean,
    val falsePositive: Boolean
)

@Entity(tableName = "reviews")
data class ReviewEntity(
    @PrimaryKey val id: String,
    val camera: String,
    val thumbTime: Double,
    val severity: String,
    val viewed: Boolean,
    val mainEventId: String,
    val labelsCsv: String,
    val json: String
)

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey val name: String,
    val enabled: Boolean,
    val detectEnabled: Boolean,
    val recordEnabled: Boolean,
    val hasSub: Boolean
)

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY since DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    fun byId(id: String): Flow<EventEntity?>

    @Upsert
    suspend fun upsert(events: List<EventEntity>)

    @Query("DELETE FROM events WHERE since < :before")
    suspend fun deleteOlder(before: Double)
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM reviews ORDER BY thumbTime DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ReviewEntity>>

    @Upsert
    suspend fun upsert(reviews: List<ReviewEntity>)

    @Query("UPDATE reviews SET viewed = :viewed WHERE id IN (:ids)")
    suspend fun setViewed(ids: List<Long>, viewed: Boolean)

    @Query("DELETE FROM reviews WHERE thumbTime < :before")
    suspend fun deleteOlder(before: Double)
}

@Dao
interface CameraDao {
    @Query("SELECT * FROM cameras ORDER BY name")
    fun all(): Flow<List<CameraEntity>>

    @Upsert
    suspend fun upsert(cameras: List<CameraEntity>)
}

@Database(entities = [EventEntity::class, ReviewEntity::class, CameraEntity::class], version = 2, exportSchema = false)
abstract class FrigateDb : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun reviewDao(): ReviewDao
    abstract fun cameraDao(): CameraDao

    companion object {
        fun build(context: Context): FrigateDb =
            Room.databaseBuilder(context, FrigateDb::class.java, "frgate_cache")
                .fallbackToDestructiveMigration()
                .build()
    }
}
