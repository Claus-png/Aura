package com.teddybear.aura.download

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.teddybear.aura.data.TrackMeta
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG              = "GrooveAudio"
private const val NORMALIZE_TIMEOUT = 90L   // seconds — hard cap, prevents 95% hang

object AudioProcessor {

    /**
     * Read audio file tolerating NumberFormatException thrown by jaudiotagger
     * when the TCON (genre) ID3 frame contains a plain string like "ruspop"
     * instead of the bracketed numeric form "(12)". When this happens we strip
     * the ID3v2 header at the byte level (no parse needed) and re-read, so
     * jaudiotagger gets a clean file and tagOrCreateDefault builds a fresh tag.
     */
    private fun readAudioFileSafe(file: File): org.jaudiotagger.audio.AudioFile? {
        return try {
            AudioFileIO.read(file)
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Non-standard genre tag in ${file.name} (${e.message}) — stripping ID3v2 and retrying")
            stripId3v2Header(file)
            try { AudioFileIO.read(file) } catch (e2: Exception) {
                Log.e(TAG, "Re-read after strip also failed: ${e2.message}")
                null
            }
        }
    }

    /**
     * Remove the ID3v2 block at the start of an MP3 by raw byte arithmetic —
     * no tag parsing involved, so it never throws on malformed frames.
     */
    private fun stripId3v2Header(file: File) {
        try {
            val bytes = file.readBytes()
            if (bytes.size > 10 &&
                bytes[0] == 'I'.code.toByte() &&
                bytes[1] == 'D'.code.toByte() &&
                bytes[2] == '3'.code.toByte()
            ) {
                // Bytes 6-9 encode the tag size as a synchsafe integer (7 bits per byte)
                val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or
                              ((bytes[7].toInt() and 0x7F) shl 14) or
                              ((bytes[8].toInt() and 0x7F) shl  7) or
                               (bytes[9].toInt() and 0x7F)
                file.writeBytes(bytes.drop(tagSize + 10).toByteArray())
                Log.d(TAG, "Stripped ${tagSize + 10} bytes of ID3v2 from ${file.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "stripId3v2Header failed for ${file.name}: ${e.message}")
        }
    }

    /**
     * Extract embedded cover art from an audio file.
     * Returns raw JPEG/PNG bytes, or null if no artwork is found.
     */
    fun extractCover(file: File): ByteArray? {
        return try {
            val af  = readAudioFileSafe(file) ?: return null
            val tag = af.tag ?: return null
            tag.firstArtwork?.binaryData
        } catch (e: Exception) {
            Log.w(TAG, "extractCover ${file.name}: ${e.message}")
            null
        }
    }

    fun writeTags(file: File, meta: TrackMeta) {
        if (!file.exists()) return
        try {
            val audioFile = readAudioFileSafe(file)
                ?: run { Log.w(TAG, "Skipping tag write — could not open ${file.name}"); return }
            val tag = audioFile.tagOrCreateDefault
            meta.title.ifNotEmpty      { tag.setField(FieldKey.TITLE,        it) }
            meta.artist.ifNotEmpty     { tag.setField(FieldKey.ARTIST,       it) }
            meta.album.ifNotEmpty      { tag.setField(FieldKey.ALBUM,        it) }
            meta.albumArtist.ifNotEmpty{ tag.setField(FieldKey.ALBUM_ARTIST, it) }
            meta.genre.ifNotEmpty      { tag.setField(FieldKey.GENRE,        it) }
            meta.year?.let             { tag.setField(FieldKey.YEAR,         it.toString()) }
            meta.trackNumber?.let      { tag.setField(FieldKey.TRACK,        it.toString()) }
            meta.lyricsPlain?.ifNotEmpty { tag.setField(FieldKey.LYRICS, it) }

            meta.coverBytes?.let { bytes ->
                try {
                    val tmp = File.createTempFile("cover", ".jpg")
                    tmp.writeBytes(bytes)
                    val artwork = ArtworkFactory.createArtworkFromFile(tmp)
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                    tmp.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Cover embed failed: ${e.message}")
                }
            }
            audioFile.commit()
        } catch (e: Exception) {
            Log.e(TAG, "writeTags failed: ${e.message}")
        }
    }

    /**
     * ReplayGain normalization with a hard timeout.
     * If FFmpeg doesn't finish within [NORMALIZE_TIMEOUT] seconds, the session
     * is cancelled and the original file is returned unchanged — preventing
     * the worker from hanging at 95%.
     */
    fun normalizeReplayGain(inputFile: File): File {
        if (!inputFile.exists() || inputFile.length() == 0L) return inputFile

        val outFile = File(inputFile.parent, inputFile.nameWithoutExtension + "_norm.mp3")

        return try {
            val cmd = "-i \"${inputFile.absolutePath}\" " +
                      "-af loudnorm=I=-16:TP=-1.5:LRA=11 " +
                      "-ar 44100 -ab 320k -y " +
                      "\"${outFile.absolutePath}\""

            val latch   = CountDownLatch(1)
            var success = false
            var sessId  = 0L

            val session = FFmpegKit.executeAsync(cmd) { s ->
                success = ReturnCode.isSuccess(s.returnCode)
                latch.countDown()
            }
            sessId = session.sessionId

            val finished = latch.await(NORMALIZE_TIMEOUT, TimeUnit.SECONDS)

            if (!finished) {
                Log.w(TAG, "FFmpeg timed out after ${NORMALIZE_TIMEOUT}s — skipping normalization")
                FFmpegKit.cancel(sessId)
                outFile.delete()
                return inputFile
            }

            if (success && outFile.exists() && outFile.length() > 0L) {
                inputFile.delete()
                outFile.renameTo(inputFile)
                Log.d(TAG, "Normalized: ${inputFile.name}")
                inputFile
            } else {
                outFile.delete()
                inputFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "normalizeReplayGain exception: ${e.message}")
            outFile.delete()
            inputFile
        }
    }

    fun ensureMp3(inputFile: File): File {
        if (inputFile.extension.lowercase() == "mp3") return inputFile
        val outFile = File(inputFile.parent, inputFile.nameWithoutExtension + ".mp3")
        return try {
            val latch   = CountDownLatch(1)
            var success = false
            FFmpegKit.executeAsync(
                "-i \"${inputFile.absolutePath}\" -ab 320k -map_metadata 0 -y \"${outFile.absolutePath}\""
            ) { s -> success = ReturnCode.isSuccess(s.returnCode); latch.countDown() }
            latch.await(60, TimeUnit.SECONDS)
            if (success && outFile.exists()) { inputFile.delete(); outFile }
            else { outFile.delete(); inputFile }
        } catch (e: Exception) {
            outFile.delete(); inputFile
        }
    }

    private fun String.ifNotEmpty(block: (String) -> Unit) {
        if (this.isNotEmpty()) block(this)
    }
}
