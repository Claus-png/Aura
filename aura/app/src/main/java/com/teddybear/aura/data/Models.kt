package com.teddybear.aura.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

// ═══════════════════════════════════════════════════════════════════════════════
// Room Entities
// ═══════════════════════════════════════════════════════════════════════════════

@Entity(tableName = "tracks", indices = [androidx.room.Index(value = ["sha256"], unique = true)])
data class Track(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val filePath: String = "",          // absolute path on device storage
    val filename: String = "",
    val title: String = "Unknown",
    val artist: String = "Unknown",
    val album: String = "",
    val albumArtist: String = "",
    val year: Int? = null,
    val genre: String = "",
    val trackNumber: Int? = null,
    val durationSec: Double? = null,
    val fileSize: Long = 0,
    val sha256: String = "",
    val coverPath: String? = null,      // local path to extracted cover art
    val lyricsPlain: String? = null,
    val lyricsLrc: String? = null,      // synchronized LRC format
    val source: String = "",            // "yandex" | "spotify" | "soundcloud" | "ytmusic" | "local"
    val sourceUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
    val rating: Float = 0f,
    val replayGain: Float? = null,      // dB value from ffmpeg ReplayGain analysis
) {
    val durationFormatted: String
        get() {
            val sec = durationSec ?: return ""
            val m = (sec / 60).toInt()
            val s = (sec % 60).toInt()
            return "$m:${s.toString().padStart(2, '0')}"
        }

    val sourceLabel: String
        get() = when (source) {
            "yandex"     -> "Яндекс Музыка"
            "spotify"    -> "Spotify"
            "soundcloud" -> "SoundCloud"
            "ytmusic"    -> "YT Music"
            "local"      -> "Локальный"
            else         -> source
        }

    val sourceColor: Long
        get() = when (source) {
            "yandex"     -> 0xFFfc3f1d
            "spotify"    -> 0xFF1db954
            "soundcloud" -> 0xFFff5500
            "ytmusic"    -> 0xFFff2828
            else         -> 0xFF8B5CF6
        }
}

@Entity(tableName = "download_jobs")
data class DownloadJob(
    @PrimaryKey
    val id: String = "",
    val url: String = "",
    val source: String = "",
    val title: String = "",
    val artist: String = "",
    val status: String = "queued",      // queued | downloading | tagging | done | error | cancelled
    val progress: Float = 0f,
    val errorMsg: String? = null,
    val trackId: Int? = null,           // filled once done
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class PlaylistWithCount(
    @Embedded val playlist: Playlist,
    @ColumnInfo(name = "trackCount") val trackCount: Int,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"])
data class PlaylistTrack(
    val playlistId: Int,
    val trackId: Int,
    val position: Int = 0,
)

// ═══════════════════════════════════════════════════════════════════════════════
// Non-Room helpers
// ═══════════════════════════════════════════════════════════════════════════════

data class LibraryStats(
    val trackCount: Int = 0,
    val totalSizeMb: Float = 0f,
    val totalHours: Float = 0f,
)

/** Lightweight DTO used during download before the track is persisted to Room. */
data class TrackMeta(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: Int? = null,
    val genre: String = "",
    val trackNumber: Int? = null,
    val durationSec: Double? = null,
    val coverBytes: ByteArray? = null,
    val lyricsPlain: String? = null,
    val lyricsLrc: String? = null,
    val source: String = "",
    val sourceUrl: String = "",
)
