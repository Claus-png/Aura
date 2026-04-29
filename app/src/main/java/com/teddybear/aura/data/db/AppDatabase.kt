package com.teddybear.aura.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.teddybear.aura.data.*
import kotlinx.coroutines.flow.Flow

// ═══════════════════════════════════════════════════════════════════════════════
// Track DAO
// ═══════════════════════════════════════════════════════════════════════════════

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(offset: Int = 0, limit: Int = Int.MAX_VALUE): List<Track>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Int): Track?

    @Query("""
        SELECT * FROM tracks
        WHERE title LIKE '%' || :q || '%'
           OR artist LIKE '%' || :q || '%'
           OR album LIKE '%' || :q || '%'
        ORDER BY title ASC
        LIMIT 50
    """)
    suspend fun search(q: String): List<Track>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC LIMIT :n")
    suspend fun getRecent(n: Int = 20): List<Track>

    @Query("SELECT * FROM tracks ORDER BY lastPlayedAt DESC LIMIT :n")
    suspend fun getRecentlyPlayed(n: Int = 20): List<Track>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Delete
    suspend fun delete(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :ts WHERE id = :id")
    suspend fun incrementPlayCount(id: Int, ts: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM tracks")
    suspend fun totalSizeBytes(): Long

    @Query("SELECT COALESCE(SUM(durationSec), 0.0) FROM tracks")
    suspend fun totalDurationSec(): Double

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :ts WHERE id = :id AND :elapsed >= 15000")
    suspend fun incrementPlayCountIfListened(id: Int, elapsed: Long, ts: Long = System.currentTimeMillis())

    @Query("UPDATE tracks SET playCount = 0 WHERE 1=1")
    suspend fun resetAllPlayCounts()

    @Query("SELECT * FROM tracks WHERE sha256 = :sha LIMIT 1")
    suspend fun getBySha(sha: String): Track?

    @Query("SELECT * FROM tracks ORDER BY playCount DESC")
    suspend fun getByPlayCount(): List<Track>

    @Query("SELECT * FROM tracks ORDER BY playCount ASC")
    suspend fun getByPlayCountAsc(): List<Track>

    @Query("UPDATE tracks SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: Int, rating: Float)

    @Query("SELECT * FROM tracks WHERE rating >= :minRating ORDER BY rating DESC, addedAt DESC")
    suspend fun getFavorites(minRating: Float = 4f): List<Track>

    @Query("SELECT * FROM tracks ORDER BY rating DESC, addedAt DESC")
    suspend fun getByRatingDesc(): List<Track>
}

// ═══════════════════════════════════════════════════════════════════════════════
// Download Job DAO
// ═══════════════════════════════════════════════════════════════════════════════

@Dao
interface DownloadJobDao {

    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<DownloadJob>>

    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC LIMIT 50")
    suspend fun getRecent(): List<DownloadJob>

    @Query("SELECT * FROM download_jobs WHERE status IN ('queued','downloading','tagging')")
    suspend fun getActive(): List<DownloadJob>

    @Query("SELECT * FROM download_jobs WHERE id = :id")
    suspend fun getById(id: String): DownloadJob?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: DownloadJob)

    @Update
    suspend fun update(job: DownloadJob)

    @Query("UPDATE download_jobs SET status = :status, progress = :progress WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, progress: Float = 0f)

    @Query("UPDATE download_jobs SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float)

    @Query("UPDATE download_jobs SET status = 'done', progress = 100.0, trackId = :trackId WHERE id = :id")
    suspend fun markDone(id: String, trackId: Int)

    @Query("UPDATE download_jobs SET status = 'error', errorMsg = :error WHERE id = :id")
    suspend fun markError(id: String, error: String)

    @Query("UPDATE download_jobs SET title = :title, artist = :artist WHERE id = :id")
    suspend fun updateMeta(id: String, title: String, artist: String)

    @Query("DELETE FROM download_jobs WHERE status IN ('done','error','cancelled')")
    suspend fun clearFinished()

    @Query("DELETE FROM download_jobs")
    suspend fun clearAll()
}

// ═══════════════════════════════════════════════════════════════════════════════
// Playlist DAO
// ═══════════════════════════════════════════════════════════════════════════════

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Playlist>>

    @Query("""
        SELECT p.*, COUNT(pt.trackId) AS trackCount
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.createdAt DESC
    """)
    fun observeAllWithCount(): Flow<List<PlaylistWithCount>>

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Delete
    suspend fun delete(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrack(pt: PlaylistTrack)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :pid AND trackId = :tid")
    suspend fun removeTrack(pid: Int, tid: Int)

    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
    """)
    suspend fun getTracksForPlaylist(playlistId: Int): List<Track>
}

// ═══════════════════════════════════════════════════════════════════════════════
// Room Database
// ═══════════════════════════════════════════════════════════════════════════════
@Database(
    entities = [Track::class, DownloadJob::class, Playlist::class, PlaylistTrack::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun jobDao(): DownloadJobDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN rating REAL NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove duplicates based on sha256, keeping the first (oldest) entry
                db.execSQL("""
                    DELETE FROM tracks WHERE id NOT IN (
                        SELECT MIN(id) FROM tracks GROUP BY sha256
                    )
                """)
                // Create new table with unique constraint
                db.execSQL("""
                    CREATE TABLE tracks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        filePath TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        albumArtist TEXT NOT NULL,
                        year INTEGER,
                        genre TEXT NOT NULL,
                        trackNumber INTEGER,
                        durationSec REAL,
                        fileSize INTEGER NOT NULL,
                        sha256 TEXT NOT NULL UNIQUE,
                        coverPath TEXT,
                        lyricsPlain TEXT,
                        lyricsLrc TEXT,
                        source TEXT NOT NULL,
                        sourceUrl TEXT,
                        addedAt INTEGER NOT NULL,
                        lastPlayedAt INTEGER,
                        playCount INTEGER NOT NULL,
                        rating REAL NOT NULL DEFAULT 0
                    )
                """)
                // Copy data from old table
                db.execSQL("""
                    INSERT INTO tracks_new SELECT * FROM tracks
                """)
                // Drop old table and rename new one
                db.execSQL("DROP TABLE tracks")
                db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
                // Create index for better performance
                db.execSQL("CREATE INDEX idx_tracks_sha256 ON tracks(sha256)")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "groove.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
