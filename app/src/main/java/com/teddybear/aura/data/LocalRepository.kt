package com.teddybear.aura.data

import android.content.Context
import com.teddybear.aura.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for the app.
 * All data comes from Room (local SQLite) — no remote server calls.
 */
class LocalRepository(context: Context) {

    private val db    = AppDatabase.get(context)
    private val track = db.trackDao()
    private val job   = db.jobDao()
    private val pl    = db.playlistDao()

    // ── Tracks ────────────────────────────────────────────────────────────────

    fun observeTracks(): Flow<List<Track>> = track.observeAll()

    suspend fun getTracks(offset: Int = 0, limit: Int = 100): List<Track> =
        track.getAll(offset, limit)

    suspend fun getTrack(id: Int): Track? = track.getById(id)

    suspend fun searchTracks(q: String): List<Track> = track.search(q)
    suspend fun getTrackBySha(sha: String): Track? = track.getBySha(sha)

    suspend fun getRecent(n: Int = 20): List<Track> = track.getRecent(n)

    suspend fun getRecentlyPlayed(n: Int = 20): List<Track> = track.getRecentlyPlayed(n)

    suspend fun insertTrack(t: Track): Int = track.insert(t).toInt()

    suspend fun updateTrack(t: Track) = track.update(t)

    suspend fun deleteTrack(id: Int, deleteFile: Boolean) {
        val t = track.getById(id) ?: return
        if (deleteFile) {
            try { java.io.File(t.filePath).delete() } catch (_: Exception) {}
            try { t.coverPath?.let { java.io.File(it).delete() } } catch (_: Exception) {}
        }
        track.deleteById(id)
    }

    suspend fun markPlayed(id: Int, elapsedMs: Long = 0L) {
        if (elapsedMs >= 15_000L) {
            track.incrementPlayCount(id)
        }
    }

    suspend fun markPlayedIfListened(id: Int, elapsedMs: Long) =
        track.incrementPlayCountIfListened(id, elapsedMs)

    suspend fun resetAllStats() = track.resetAllPlayCounts()

    suspend fun getTracksByPlayCountDesc() = track.getByPlayCount()
    suspend fun getTracksByPlayCountAsc()  = track.getByPlayCountAsc()
    suspend fun getFavoriteTracks(minRating: Float = 4f) = track.getFavorites(minRating)
    suspend fun getTracksByRatingDesc() = track.getByRatingDesc()
    suspend fun setTrackRating(id: Int, rating: Float) = track.setRating(id, rating)

    suspend fun getStats(): LibraryStats {
        val count    = track.count()
        val bytes    = track.totalSizeBytes()
        val durSec   = track.totalDurationSec()
        return LibraryStats(
            trackCount  = count,
            totalSizeMb = (bytes / 1_048_576f),
            totalHours  = (durSec / 3600f).toFloat(),
        )
    }

    // ── Download Jobs ─────────────────────────────────────────────────────────

    fun observeJobs(): Flow<List<DownloadJob>> = job.observeRecent()

    suspend fun getJobs(): List<DownloadJob> = job.getRecent()

    suspend fun getActiveJobs(): List<DownloadJob> = job.getActive()

    suspend fun createJob(jobId: String, url: String, source: String) =
        job.insert(DownloadJob(id = jobId, url = url, source = source))

    suspend fun updateJobProgress(jobId: String, progress: Float) =
        job.updateProgress(jobId, progress)

    suspend fun updateJobStatus(jobId: String, status: String, progress: Float = 0f) =
        job.updateStatus(jobId, status, progress)

    suspend fun updateJobMeta(jobId: String, title: String, artist: String) =
        job.updateMeta(jobId, title, artist)

    suspend fun markJobDone(jobId: String, trackId: Int) =
        job.markDone(jobId, trackId)

    suspend fun markJobError(jobId: String, error: String) =
        job.markError(jobId, error)

    suspend fun clearFinishedJobs() = job.clearFinished()
    suspend fun clearAllJobs()      = job.clearAll()

    // ── Playlists ─────────────────────────────────────────────────────────────

    fun observePlaylists(): Flow<List<Playlist>> = pl.observeAll()

    fun observePlaylistsWithCount(): Flow<List<PlaylistWithCount>> = pl.observeAllWithCount()

    suspend fun createPlaylist(name: String): Int = pl.insert(Playlist(name = name)).toInt()

    suspend fun deletePlaylist(playlist: Playlist) = pl.delete(playlist)

    suspend fun addTrackToPlaylist(playlistId: Int, trackId: Int, position: Int = 0) =
        pl.addTrack(PlaylistTrack(playlistId, trackId, position))

    suspend fun removeTrackFromPlaylist(playlistId: Int, trackId: Int) =
        pl.removeTrack(playlistId, trackId)

    suspend fun getPlaylistTracks(playlistId: Int): List<Track> =
        pl.getTracksForPlaylist(playlistId)
}
