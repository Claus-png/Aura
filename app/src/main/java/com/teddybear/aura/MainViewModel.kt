package com.teddybear.aura

import android.util.Log
import android.app.Application
import android.os.Bundle
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.work.WorkManager
import com.teddybear.aura.data.*
import com.teddybear.aura.download.buildDownloadRequest
import com.teddybear.aura.crypto.P2PECrypto
import com.teddybear.aura.network.ProxyConfig
import com.teddybear.aura.network.SecureStorage
import com.teddybear.aura.network.ServerRepository
import com.teddybear.aura.player.BluetoothProfileManager
import com.teddybear.aura.player.EqState
import com.teddybear.aura.player.EqualizerManager
import com.teddybear.aura.player.GroovePlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val prefs     = AppPreferences(app)
    val repo      = LocalRepository(app)

    // ── Preferences ───────────────────────────────────────────────────────────
    // ── Server connection ──────────────────────────────────────────────────────
    val serverRepo = ServerRepository(app)

    private val _serverOnline = MutableStateFlow(false)
    val serverOnline: StateFlow<Boolean> = _serverOnline

    private val _yandexLogin = MutableStateFlow<String?>(null)
    val yandexLogin: StateFlow<String?> = _yandexLogin

    // Legacy proxy prefs kept for downloads via ExternalToolRunner
    val proxyEnabled    = prefs.proxyEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val proxyType       = prefs.proxyType.stateIn(viewModelScope, SharingStarted.Eagerly, "SOCKS5")
    val proxyHost       = prefs.proxyHost.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val proxyPort       = prefs.proxyPort.stateIn(viewModelScope, SharingStarted.Eagerly, 1080)
    val proxyUser       = prefs.proxyUser.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val audioBitrate    = prefs.audioBitrate.stateIn(viewModelScope, SharingStarted.Eagerly, "auto")
    val normalizeVolume = prefs.normalizeVolume.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val trackStats      = prefs.trackStats.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dynNameAll      = prefs.dynNameAll.stateIn(viewModelScope, SharingStarted.Eagerly, "Библиотека")
    val dynNameFrequent = prefs.dynNameFrequent.stateIn(viewModelScope, SharingStarted.Eagerly, "Часто слушаю")
    val dynNameRare     = prefs.dynNameRare.stateIn(viewModelScope, SharingStarted.Eagerly, "Редкие треки")
    val proxyBannerDismissed = prefs.proxyBannerDismissed.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissProxyBanner() = viewModelScope.launch { prefs.saveProxyBannerDismissed(true) }

    // ── Library ───────────────────────────────────────────────────────────────
    val tracks = repo.observeTracks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _stats = MutableStateFlow<LibraryStats?>(null)
    val stats: StateFlow<LibraryStats?> = _stats

    private val _frequentTracks = MutableStateFlow<List<Track>>(emptyList())
    val frequentTracks: StateFlow<List<Track>> = _frequentTracks

    private val _rareTracks = MutableStateFlow<List<Track>>(emptyList())
    val rareTracks: StateFlow<List<Track>> = _rareTracks

    private val _favoriteTracks = MutableStateFlow<List<Track>>(emptyList())
    val favoriteTracks: StateFlow<List<Track>> = _favoriteTracks

    private val _ratedTracks = MutableStateFlow<List<Track>>(emptyList())
    val ratedTracks: StateFlow<List<Track>> = _ratedTracks

    // Track how long current track has been playing (for 15s rule)
    private var currentTrackStartMs = 0L

    val jobs = repo.observeJobs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _recentTracks   = MutableStateFlow<List<Track>>(emptyList())
    val recentTracks: StateFlow<List<Track>> = _recentTracks

    private val _recentlyPlayed = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recentlyPlayed

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults

    // ── Playlists ─────────────────────────────────────────────────────────────
    val playlists = repo.observePlaylistsWithCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _playlistTracks = MutableStateFlow<List<Track>>(emptyList())
    val playlistTracks: StateFlow<List<Track>> = _playlistTracks

    // ── Player ────────────────────────────────────────────────────────────────
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode

    private val _shuffleOn = MutableStateFlow(false)
    val shuffleOn: StateFlow<Boolean> = _shuffleOn

    var player: MediaController? = null
        private set

    // ── Region / proxy ────────────────────────────────────────────────────────
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Proxy link input — persists across tab switches
    private val _proxyLinkInput = MutableStateFlow("")
    val proxyLinkInput: StateFlow<String> = _proxyLinkInput

    fun setProxyLinkInput(link: String) { _proxyLinkInput.value = link }

    private val _regionCode = MutableStateFlow("")
    val regionCode: StateFlow<String> = _regionCode

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive

    val proxyRequired: StateFlow<Boolean> = combine(_regionCode, _vpnActive, proxyEnabled) { region, vpn, proxyOn ->
        region == "RU" && !vpn && !proxyOn
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _eqState = MutableStateFlow(EqState())
    val eqState: StateFlow<EqState> = _eqState
    val eqBandFrequencies: List<String> get() = EqualizerManager.DEFAULT_BAND_FREQUENCIES
    val eqBandMin: Int get() = EqualizerManager.DEFAULT_BAND_MIN
    val eqBandMax: Int get() = EqualizerManager.DEFAULT_BAND_MAX

    val btProfileManager = BluetoothProfileManager(
        context = app,
        getEqState = { _eqState.value },
        applyEqState = ::applyEqStateFromDeviceProfile,
    )
    val bluetoothPendingDevice get() = btProfileManager.pendingDevice
    val btCollectionEnabled: Boolean get() = btProfileManager.collectionEnabled

    private val musicDirObservers = mutableListOf<android.os.FileObserver>()
    private var heartbeatJob: Job? = null
    private val processLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> startHeartbeat()
            Lifecycle.Event.ON_STOP -> stopHeartbeat()
            else -> Unit
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        SecureStorage.init(app)
        SecureStorage.getMasterKey()?.let { P2PECrypto.loadMasterKey(it) }
        _yandexLogin.value = SecureStorage.getYandexLogin()
        btProfileManager.register()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        viewModelScope.launch(Dispatchers.IO) { loadLibraryData(includeDynamic = true) }
        viewModelScope.launch(Dispatchers.IO) { scanMusicDirectory() }
        startMusicDirWatcher()
        viewModelScope.launch(Dispatchers.IO) { checkServerStatus() }
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            startHeartbeat()
        }

        // Refresh stats when library changes — debounced to avoid hammering DB on bulk downloads
        viewModelScope.launch {
            tracks.drop(1)
                .debounce(2000)
                .collect { loadLibraryData() }
        }
        viewModelScope.launch {
            prefs.eqState.collect { _eqState.value = it }
        }
    }

    // Set to false when stats change so next load refreshes dynamic playlists
    private var dynamicPlaylistsLoaded = false
    private var scanInProgress = false
    private var lastScanTime = 0L

    private suspend fun loadLibraryData(includeDynamic: Boolean = false) {
        _stats.value          = repo.getStats()
        _recentTracks.value   = repo.getRecent(20)
        _recentlyPlayed.value = repo.getRecentlyPlayed(20)
        // Dynamic playlists: expensive sort — only compute at startup or on explicit refresh
        if (includeDynamic || !dynamicPlaylistsLoaded) {
            _frequentTracks.value   = repo.getTracksByRatingDesc()
            _rareTracks.value       = repo.getTracksByRatingDesc()
            _favoriteTracks.value   = repo.getFavoriteTracks()
            _ratedTracks.value      = repo.getTracksByRatingDesc()
            dynamicPlaylistsLoaded  = true
        }
    }

    // ── Player init ───────────────────────────────────────────────────────────
    fun initPlayer(app: Application) {
        val token  = SessionToken(app, ComponentName(app, GroovePlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val ctrl = runCatching { future.get() }.getOrNull() ?: return@addListener
            player = ctrl
            // Restore saved playback modes
            viewModelScope.launch {
                ctrl.repeatMode        = prefs.repeatMode.first()
                ctrl.shuffleModeEnabled = prefs.shuffleOn.first()
            }
            ctrl.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(ip: Boolean) { _isPlaying.value = ip }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    // Record listen time for the track we just left
                    val previous = _currentTrack.value
                    if (previous != null) {
                        val elapsed = System.currentTimeMillis() - currentTrackStartMs
                        if (trackStats.value) {
                            viewModelScope.launch(Dispatchers.IO) {
                                repo.markPlayedIfListened(previous.id, elapsed)
                                if (elapsed >= 15_000L) {
                                    dynamicPlaylistsLoaded = false
                                    loadLibraryData(includeDynamic = true)
                                }
                            }
                        }
                    }
                    // Start timer for new track
                    currentTrackStartMs = System.currentTimeMillis()
                    val idStr = item?.mediaId?.toIntOrNull() ?: return
                    _currentTrack.value = _queue.value.find { it.id == idStr }
                }
                override fun onRepeatModeChanged(rm: Int) { _repeatMode.value = rm }
                override fun onShuffleModeEnabledChanged(se: Boolean) { _shuffleOn.value = se }
                // onEvents kept for immediate sync on seek
                override fun onEvents(p: Player, events: Player.Events) {
                    _position.value = p.currentPosition.coerceAtLeast(0L)
                    if (p.duration > 0) _duration.value = p.duration
                }
                override fun onPlaybackStateChanged(state: Int) {
                    // Track natural completion (ENDED) — count as listened
                    if (state == Player.STATE_ENDED) {
                        val current = _currentTrack.value ?: return
                        if (!trackStats.value) return
                        val elapsed = System.currentTimeMillis() - currentTrackStartMs
                        // If played >15s OR track is naturally short, count as listened
                        val dur = _duration.value
                        if (elapsed >= 15_000L || (dur in 1..14_999)) {
                            viewModelScope.launch(Dispatchers.IO) {
                                repo.markPlayed(current.id)
                            }
                        }
                        currentTrackStartMs = System.currentTimeMillis()
                    }
                }
            })
            // 500ms position ticker for smooth progress bar
            viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(500)
                    if (_isPlaying.value) {
                        _position.value = ctrl.currentPosition.coerceAtLeast(0L)
                        val dur = ctrl.duration
                        if (dur > 0L) _duration.value = dur
                    }
                }
            }

            pushEqStateToService(_eqState.value)
        }, ContextCompat.getMainExecutor(app))
    }

    // ── Library ───────────────────────────────────────────────────────────────
    fun refreshLibrary(full: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        if (full) dynamicPlaylistsLoaded = false
        loadLibraryData(includeDynamic = full)
    }

    fun search(q: String) = viewModelScope.launch(Dispatchers.IO) {
        _searchResults.value = if (q.isBlank()) emptyList() else repo.searchTracks(q)
    }

    fun deleteTrack(trackId: Int, deleteFile: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        repo.deleteTrack(trackId, deleteFile)
        refreshLibrary()
    }

    // ── Playlists ─────────────────────────────────────────────────────────────
    fun loadPlaylistTracks(playlistId: Int) = viewModelScope.launch(Dispatchers.IO) {
        _playlistTracks.value = repo.getPlaylistTracks(playlistId)
    }

    fun createPlaylist(name: String) = viewModelScope.launch(Dispatchers.IO) {
        repo.createPlaylist(name)
    }

    fun deletePlaylist(playlist: Playlist) = viewModelScope.launch(Dispatchers.IO) {
        repo.deletePlaylist(playlist)
    }

    fun addTrackToPlaylist(playlistId: Int, trackId: Int) = viewModelScope.launch(Dispatchers.IO) {
        repo.addTrackToPlaylist(playlistId, trackId)
    }

    fun removeTrackFromPlaylist(playlistId: Int, trackId: Int) = viewModelScope.launch(Dispatchers.IO) {
        repo.removeTrackFromPlaylist(playlistId, trackId)
        // Refresh if currently viewing this playlist
        if (_playlistTracks.value.any { it.id == trackId }) {
            loadPlaylistTracks(playlistId)
        }
    }

    /** Import an entire folder of audio files from SAF URI (OpenDocumentTree). */
    fun importFolder(treeUri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val ctx = getApplication<Application>()
            // Persist permission so it survives reboots
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(treeUri, flags)

            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri)
                ?: return@launch
            val audioExtensions = setOf("mp3", "flac", "m4a", "ogg", "aac", "wav", "opus")

            fun processDir(dir: androidx.documentfile.provider.DocumentFile) {
                dir.listFiles().forEach { f ->
                    when {
                        f.isDirectory -> processDir(f)
                        f.name?.substringAfterLast('.', "")?.lowercase() in audioExtensions -> {
                            try { importLocalFile(f.uri) } catch (_: Exception) {}
                        }
                    }
                }
            }
            processDir(docFile)
            _error.value = null
        } catch (e: Exception) {
            _error.value = "Ошибка импорта папки: ${e.message}"
        }
    }

    /** Extract and cache cover from a local audio file. Returns cover file path or null. */
    private fun extractAndCacheCover(audioFile: java.io.File, trackId: Int): String? {
        return try {
            val coverBytes = com.teddybear.aura.download.AudioProcessor.extractCover(audioFile)
                ?: return null
            val coversDir = java.io.File(getApplication<Application>().filesDir, "covers")
            coversDir.mkdirs()
            val coverFile = java.io.File(coversDir, "cover_$trackId.jpg")
            coverFile.writeBytes(coverBytes)
            coverFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.w("GrooveVM", "Cover cache failed: ${e.message}")
            null
        }
    }

    /** Import a local audio file into the library. */
    fun importLocalFile(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val ctx       = getApplication<Application>()
            val contentResolver = ctx.contentResolver

            // Resolve display name
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: "local_${System.currentTimeMillis()}.mp3"

            // Copy to local storage
            val destDir = File(ctx.getExternalFilesDir("Music"), "local").also { it.mkdirs() }
            val destFile = File(destDir, name)
            contentResolver.openInputStream(uri)?.use { inp ->
                destFile.outputStream().use { out -> inp.copyTo(out) }
            }

            // Read tags + extract embedded cover art
            val (meta, coverBytes) = try {
                val audio = org.jaudiotagger.audio.AudioFileIO.read(destFile)
                val tag   = audio.tag
                val artworkBytes = try {
                    tag?.firstArtwork?.binaryData
                } catch (_: Exception) { null }
                TrackMeta(
                    title       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)        ?.ifEmpty { null } ?: destFile.nameWithoutExtension,
                    artist      = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)       ?.ifEmpty { null } ?: "Unknown",
                    album       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM)        ?: "",
                    albumArtist = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST) ?: "",
                    year        = tag?.getFirst(org.jaudiotagger.tag.FieldKey.YEAR)?.toIntOrNull(),
                    genre       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.GENRE)        ?: "",
                    durationSec = audio.audioHeader.trackLength.toDouble(),
                    source      = "local",
                ) to artworkBytes
            } catch (e: Exception) {
                TrackMeta(title = destFile.nameWithoutExtension, source = "local") to null
            }

            // Compute SHA-256 first (used for cover filename and dedup)
            val sha = run {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                destFile.inputStream().use { inp ->
                    val buf = ByteArray(8192); var r: Int
                    while (inp.read(buf).also { r = it } != -1) md.update(buf, 0, r)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }

            // Save cover to disk for display
            val coverPath: String? = coverBytes?.let { bytes ->
                try {
                    val ctx2 = getApplication<Application>()
                    val coverDir = File(ctx2.filesDir, "covers").also { it.mkdirs() }
                    val coverFile = File(coverDir, "${sha}.jpg")
                    if (!coverFile.exists()) coverFile.writeBytes(bytes)
                    coverFile.absolutePath
                } catch (_: Exception) { null }
            }

            val track = Track(
                filePath    = destFile.absolutePath,
                filename    = destFile.name,
                title       = meta.title,
                artist      = meta.artist,
                album       = meta.album,
                albumArtist = meta.albumArtist,
                year        = meta.year,
                genre       = meta.genre,
                durationSec = meta.durationSec,
                fileSize    = destFile.length(),
                sha256      = sha,
                source      = "local",
                coverPath   = coverPath,
            )
            repo.insertTrack(track)
            loadLibraryData(includeDynamic = true)  // include dynamic so new tracks appear
            _error.value = null
        } catch (e: Exception) {
            _error.value = "Ошибка импорта файла: ${e.message}"
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────
    fun enqueueDownload(url: String): Boolean {
        val source = detectSource(url)

        // Non-Yandex sources require a working server — no local fallback
        if (source != "yandex") {
            if (!serverRepo.isConfigured) {
                _error.value = "Для загрузки из ${sourceLabel(source)} нужен домашний сервер. " +
                    "Настройте сервер в разделе «Настройки»."
                return false
            }
            if (!serverOnline.value) {
                _error.value = "Сервер недоступен. Проверьте подключение."
                return false
            }
        }

        val jobId = UUID.randomUUID().toString()
        // Only enable proxy if host is actually configured
        val proxyHostVal = proxyHost.value.trim()
        val proxy = ProxyConfig(
            enabled  = proxyEnabled.value && proxyHostVal.isNotBlank(),
            type     = proxyType.value,
            host     = proxyHostVal,
            port     = proxyPort.value,
            username = proxyUser.value,
            password = "",
        )

        viewModelScope.launch(Dispatchers.IO) { repo.createJob(jobId, url, source) }

        WorkManager.getInstance(getApplication()).enqueue(
            buildDownloadRequest(
                jobId     = jobId,
                url       = url,
                source    = source,
                proxy     = proxy,
                normalize = normalizeVolume.value,
                bitrate   = audioBitrate.value,
            )
        )
        return true
    }

    fun cancelDownload(jobId: String) = viewModelScope.launch(Dispatchers.IO) {
        WorkManager.getInstance(getApplication()).cancelAllWorkByTag("download_$jobId")
        repo.updateJobStatus(jobId, "cancelled")
    }

    // ── Player controls ───────────────────────────────────────────────────────
    /**
     * Shuffle 2.0 pattern for first 50 tracks.
     * R=Rare (low playCount), F=Frequent (high playCount)
     * Pattern repeats: R,F,R,R,F,R,R,F,R,R (10 per row, 5 rows = 50 total)
     */
    // Shuffle 2.0: 50-track pattern from spec (R=Rare F=Frequent)
    // Row: R F R R F R R F R F  → repeated 5 times = 50 tracks
    private val SHUFFLE_PATTERN: List<Boolean> = run {
        val row = listOf(false, true, false, false, true, false, false, true, false, true)
        List(5) { row }.flatten()
    }

    private fun buildSmartQueue(tracks: List<Track>, startTrack: Track): List<Track> {
        if (tracks.size < 4 || !_shuffleOn.value) return tracks
        val median   = tracks.map { it.playCount }.sorted().let { it[it.size / 2] }
        val rare     = tracks.filter { it.playCount <= median }.shuffled().toMutableList()
        val frequent = tracks.filter { it.playCount > median }.shuffled().toMutableList()

        val first50 = mutableListOf<Track>()
        for (isFrequent in SHUFFLE_PATTERN) {
            val src = if (isFrequent) frequent else rare
            if (src.isNotEmpty()) first50.add(src.removeAt(0))
            else {
                val other = if (isFrequent) rare else frequent
                if (other.isNotEmpty()) first50.add(other.removeAt(0))
            }
        }
        val remaining = (rare + frequent).shuffled()
        val queue     = (first50 + remaining).toMutableList()
        // Ensure startTrack is first
        queue.remove(startTrack)
        queue.add(0, startTrack)
        return queue
    }

    fun playTrack(track: Track, queue: List<Track> = emptyList()) {
        val pl = player ?: return
        val rawQ = if (queue.isEmpty()) listOf(track) else queue
        val q    = if (_shuffleOn.value && rawQ.size > 3) buildSmartQueue(rawQ, track) else {
            // Ensure selected track is first, even when shuffle is off
            val mutableQ = rawQ.toMutableList()
            mutableQ.remove(track)
            mutableQ.add(0, track)
            mutableQ
        }
        _queue.value        = q
        _currentTrack.value = track

        val items = q.map { t ->
            val uri = Uri.fromFile(File(t.filePath))
            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(t.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setArtworkUri(t.coverPath?.let { Uri.parse("file://$it") })
                        .build()
                )
                .build()
        }
        pl.setMediaItems(items, 0, 0)   // always start at index 0 (startTrack is first)
        pl.prepare()
        pl.play()
        currentTrackStartMs = System.currentTimeMillis()
    }

    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    // ── Sleep timer ────────────────────────────────────────────────────────────
    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            player?.pause()
            _error.value = "Таймер сна: воспроизведение остановлено"
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    private fun recordListenTime() {
        val current = _currentTrack.value ?: return
        if (!trackStats.value) return
        val elapsed = System.currentTimeMillis() - currentTrackStartMs
        viewModelScope.launch(Dispatchers.IO) {
            repo.markPlayedIfListened(current.id, elapsed)
            // Dynamic playlists stale after play count change
            if (elapsed >= 15_000L) {
                dynamicPlaylistsLoaded = false
                loadLibraryData(includeDynamic = true)
            }
        }
        currentTrackStartMs = System.currentTimeMillis()
    }

    fun skipNext() { recordListenTime(); player?.seekToNextMediaItem() }
    fun skipPrev() { recordListenTime(); player?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long)  { player?.seekTo(ms) }

    /** Set sleep timer. minutes=0 cancels. */
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) return
        sleepTimerJob = viewModelScope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            player?.pause()
            sleepTimerJob = null
        }
    }

    fun toggleRepeat() {
        val next = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else                   -> Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = next          // optimistic update so UI reacts instantly
        player?.repeatMode = next
        viewModelScope.launch { prefs.setRepeatMode(next) }
    }

    fun toggleShuffle() {
        val newVal = !_shuffleOn.value
        _shuffleOn.value = newVal         // optimistic update so UI reacts instantly
        player?.shuffleModeEnabled = newVal
        viewModelScope.launch { prefs.setShuffleOn(newVal) }
    }

    // ── EQ ────────────────────────────────────────────────────────────────────
    fun setEqEnabled(on: Boolean) = updateEqState { it.copy(enabled = on) }

    fun setEqBand(band: Int, value: Int) = updateEqState { state ->
        val bands = state.bands.toMutableList()
        bands[band] = value.coerceIn(eqBandMin, eqBandMax)
        state.copy(bands = bands, presetIndex = 0)
    }

    fun applyEqPreset(index: Int) {
        val preset = EqualizerManager.PRESETS.getOrNull(index) ?: return
        updateEqState { it.copy(bands = preset.bands, presetIndex = index) }
    }

    fun setEqBassBoost(strength: Int) = updateEqState { it.copy(bassBoost = strength.coerceIn(0, 1000)) }
    fun setEqVirtualizer(strength: Int) = updateEqState { it.copy(virtualizer = strength.coerceIn(0, 1000)) }

    fun setTrackRating(trackId: Int, rating: Float) = viewModelScope.launch(Dispatchers.IO) {
        repo.setTrackRating(trackId, rating.coerceIn(0f, 5f))
        dynamicPlaylistsLoaded = false
        loadLibraryData(includeDynamic = true)
    }

    // ── Settings ──────────────────────────────────────────────────────────────
    fun saveProxySettings(
        enabled: Boolean, type: String, host: String, port: Int, user: String, hasPass: Boolean,
    ) = viewModelScope.launch {
        prefs.saveProxySettings(enabled, type, host, port, user, hasPass)
    }

    fun setProxyEnabled(on: Boolean) = viewModelScope.launch { prefs.setProxyEnabled(on) }
    fun setAudioBitrate(b: String)   = viewModelScope.launch { prefs.setAudioBitrate(b) }
    fun setNormalize(on: Boolean)    = viewModelScope.launch { prefs.setNormalizeVolume(on) }
    fun setTrackStats(on: Boolean)   = viewModelScope.launch { prefs.setTrackStats(on) }
    fun setBtCollection(on: Boolean) { btProfileManager.collectionEnabled = on }
    fun resetStats()                 = viewModelScope.launch(Dispatchers.IO) { repo.resetAllStats(); loadLibraryData(includeDynamic = true) }
    fun setDynNameAll(n: String)     = viewModelScope.launch { prefs.setDynNameAll(n) }
    fun setDynNameFrequent(n: String)= viewModelScope.launch { prefs.setDynNameFrequent(n) }
    fun setDynNameRare(n: String)    = viewModelScope.launch { prefs.setDynNameRare(n) }

    // ── Region / VPN ──────────────────────────────────────────────────────────
    /** Scan app's Music directory and import any unknown files. */
    suspend fun scanMusicDirectory() {
        // Prevent concurrent scans and avoid repeated scans within 30 seconds
        if (scanInProgress || System.currentTimeMillis() - lastScanTime < 30_000L) return
        scanInProgress = true
        try {
            val ctx = getApplication<Application>()
            val musicDirs = listOf(
                File(ctx.getExternalFilesDir("Music"), "local"),
                File(ctx.getExternalFilesDir("Music"), "yandex"),
                File(ctx.getExternalFilesDir("Music"), "ytmusic"),
                File(ctx.getExternalFilesDir("Music"), "soundcloud"),
                File(ctx.getExternalFilesDir("Music"), "spotify"),
            )
            val audioExt = setOf("mp3", "flac", "m4a", "ogg", "aac", "wav", "opus")

            // Use paginated queries for better performance with large libraries
            val knownShas   = HashSet<String>()
            val knownPaths  = HashSet<String>()
            val shaCache    = HashMap<String, String>()
            
            var offset = 0
            val pageSize = 500
            while (true) {
                val page = repo.getTracks(offset, pageSize)
                if (page.isEmpty()) break
                page.forEach {
                    knownShas.add(it.sha256)
                    knownPaths.add(it.filePath)
                }
                offset += pageSize
            }

            var added = 0
            for (dir in musicDirs) {
                if (!dir.exists()) continue
                dir.walkTopDown().filter { f ->
                    f.isFile && f.extension.lowercase() in audioExt && f.absolutePath !in knownPaths
                }.forEach { file ->
                    try {
                        val inserted = importLocalFileIfNew(file, knownShas, shaCache)
                        if (inserted) {
                            added++
                            knownPaths.add(file.absolutePath)
                        }
                    } catch (_: Exception) {}
                }
            }
            if (added > 0) loadLibraryData()
        } finally {
            scanInProgress = false
            lastScanTime = System.currentTimeMillis()
        }
    }

    /**
     * Import a file only if its SHA256 is not already in [knownShas].
     * Returns true if a new track was inserted.
     */
    private suspend fun importLocalFileIfNew(
        file: File,
        knownShas: HashSet<String>,
        shaCache: MutableMap<String, String>,
    ): Boolean {
        val ctx = getApplication<Application>()
        val (meta, coverBytes) = try {
            val audio = org.jaudiotagger.audio.AudioFileIO.read(file)
            val tag   = audio.tag
            val artworkBytes = try { tag?.firstArtwork?.binaryData } catch (_: Exception) { null }
            TrackMeta(
                title       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)?.ifEmpty { null } ?: file.nameWithoutExtension,
                artist      = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)?.ifEmpty { null } ?: "Unknown",
                album       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM) ?: "",
                albumArtist = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST) ?: "",
                year        = tag?.getFirst(org.jaudiotagger.tag.FieldKey.YEAR)?.toIntOrNull(),
                genre       = tag?.getFirst(org.jaudiotagger.tag.FieldKey.GENRE) ?: "",
                durationSec = audio.audioHeader.trackLength.toDouble(),
                source      = "local",
            ) to artworkBytes
        } catch (_: Exception) {
            TrackMeta(title = file.nameWithoutExtension, source = "local") to null
        }

        val sha = shaCache.getOrPut("${file.absolutePath}:${file.length()}:${file.lastModified()}") {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { inp ->
                val buf = ByteArray(8192); var r: Int
                while (inp.read(buf).also { r = it } != -1) md.update(buf, 0, r)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

        // Skip if SHA already known — prevents duplicates even if filename/path differs
        if (sha in knownShas) return false
        knownShas.add(sha) // mark immediately so parallel calls don't double-insert

        val coverPath: String? = coverBytes?.let { bytes ->
            try {
                val coverDir = File(ctx.filesDir, "covers").also { it.mkdirs() }
                val coverFile = File(coverDir, "$sha.jpg")
                if (!coverFile.exists()) coverFile.writeBytes(bytes)
                coverFile.absolutePath
            } catch (_: Exception) { null }
        }

        repo.insertTrack(Track(
            filePath = file.absolutePath, filename = file.name,
            title = meta.title, artist = meta.artist, album = meta.album,
            albumArtist = meta.albumArtist, year = meta.year, genre = meta.genre,
            durationSec = meta.durationSec, fileSize = file.length(),
            sha256 = sha, source = meta.source, coverPath = coverPath,
        ))
        return true
    }

    private fun startMusicDirWatcher() {
        val ctx = getApplication<Application>()
        val musicBase = ctx.getExternalFilesDir("Music") ?: return
        musicBase.mkdirs()
        musicDirObservers.forEach { it.stopWatching() }
        musicDirObservers.clear()
        listOf("local", "yandex", "ytmusic", "soundcloud", "spotify").forEach { folder ->
            val watchDir = File(musicBase, folder).also { it.mkdirs() }
            val observer = object : android.os.FileObserver(
                watchDir,
                android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    val file = File(watchDir, path)
                    val ext = file.extension.lowercase()
                    if (ext in setOf("mp3", "flac", "m4a", "ogg", "aac", "wav", "opus")) {
                        viewModelScope.launch(Dispatchers.IO) {
                            delay(500)
                            // Use paginated queries instead of loading all tracks
                            val knownShas = HashSet<String>()
                            var offset = 0
                            val pageSize = 500
                            while (true) {
                                val page = repo.getTracks(offset, pageSize)
                                if (page.isEmpty()) break
                                page.forEach { knownShas.add(it.sha256) }
                                offset += pageSize
                            }
                            val shaCache = HashMap<String, String>()
                            val inserted = importLocalFileIfNew(file, knownShas, shaCache)
                            if (inserted) loadLibraryData(includeDynamic = true)
                        }
                    }
                }
            }
            observer.startWatching()
            musicDirObservers += observer
        }
    }

    /** BT profile actions */
    fun nameBluetoothDevice(device: android.bluetooth.BluetoothDevice, label: String) {
        val type = when (label) {
            "Наушники", "Гарнитура" -> BluetoothProfileManager.DeviceType.HEADPHONES
            "Колонка"               -> BluetoothProfileManager.DeviceType.SPEAKER
            "Авто"                  -> BluetoothProfileManager.DeviceType.CAR
            else                    -> BluetoothProfileManager.DeviceType.OTHER
        }
        btProfileManager.classifyDevice(device, type)
    }
    fun dismissBluetoothDevice() = btProfileManager.dismissPendingDevice()
    fun classifyBtDevice(d: android.bluetooth.BluetoothDevice, t: BluetoothProfileManager.DeviceType) =
        btProfileManager.classifyDevice(d, t)
    fun getBtCollect(): Boolean  = btProfileManager.collectionEnabled
    fun setBtCollect(on: Boolean) { btProfileManager.collectionEnabled = on }

    /** Call when app resumes to pick up any files added while in background. */
    fun onAppResume() = viewModelScope.launch(Dispatchers.IO) { scanMusicDirectory() }

    override fun onCleared() {
        heartbeatJob?.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        musicDirObservers.forEach { it.stopWatching() }
        musicDirObservers.clear()
        btProfileManager.unregister()
        super.onCleared()
    }

    private suspend fun checkServerStatus() {
        _serverOnline.value = serverRepo.checkStatus()
    }

    fun recheckServer() = viewModelScope.launch(Dispatchers.IO) { checkServerStatus() }

    /** Remove completed/failed jobs from history. */
    fun clearDownloadHistory() = viewModelScope.launch(Dispatchers.IO) { repo.clearFinishedJobs() }

    /** Remove ALL download jobs including active ones. */
    fun clearAllDownloads() = viewModelScope.launch(Dispatchers.IO) { repo.clearAllJobs() }

    /** Configure server from QR code data. */
    fun setupServer(url: String, encryptionKey: String, apiKey: String = "") {
        serverRepo.serverUrl = url
        if (encryptionKey.isNotBlank()) {
            val ok = P2PECrypto.loadMasterKey(encryptionKey)
            if (ok) SecureStorage.saveMasterKey(encryptionKey)
        }
        val keyForAuth = apiKey.ifBlank { encryptionKey }
        if (keyForAuth.isNotBlank()) {
            SecureStorage.saveApiKey(keyForAuth)
        }
        viewModelScope.launch(Dispatchers.IO) {
            checkServerStatus()
            if (serverRepo.isConfigured) serverRepo.pingServer(getApplication())
        }
    }
    fun syncYandexCookies(cookies: Map<String, String>, onDone: (Boolean) -> Unit) {
        SecureStorage.saveYandexCookies(cookies)
        _yandexLogin.value = cookies["yandex_login"]
        viewModelScope.launch(Dispatchers.IO) {
            val ok = serverRepo.syncYandexCookies(cookies)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun logoutYandex() {
        SecureStorage.clearYandexCookies()
        _yandexLogin.value = null
    }
    fun clearError()     { _error.value = null }

    private fun updateEqState(transform: (EqState) -> EqState) {
        val newState = transform(_eqState.value)
        _eqState.value = newState
        viewModelScope.launch {
            prefs.saveEqState(newState)
            pushEqStateToService(newState)
        }
    }

    private fun applyEqStateFromDeviceProfile(state: EqState) {
        updateEqState {
            it.copy(
                bands = state.bands,
                bassBoost = state.bassBoost,
                virtualizer = state.virtualizer,
                presetIndex = state.presetIndex,
            )
        }
    }

    private fun pushEqStateToService(state: EqState) {
        val controller = player ?: return
        controller.sendCustomCommand(
            SessionCommand(GroovePlaybackService.COMMAND_SET_EQ_STATE, Bundle.EMPTY),
            Bundle().apply {
                putBoolean("enabled", state.enabled)
                putIntArray("bands", state.bands.toIntArray())
                putInt("presetIndex", state.presetIndex)
                putInt("bassBoost", state.bassBoost)
                putInt("virtualizer", state.virtualizer)
            },
        )
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (serverRepo.isConfigured) {
                    try {
                        serverRepo.pingServer(getApplication())
                    } catch (e: Exception) {
                        Log.w("AURA", "Ping failed (stale tunnel?): ${e.message}")
                        checkServerStatus()
                    }
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun detectSource(url: String) = when {
        "music.yandex"   in url -> "yandex"
        "spotify.com"    in url -> "spotify"
        "soundcloud.com" in url -> "soundcloud"
        "youtube.com"    in url || "youtu.be" in url -> "ytmusic"
        "music.youtube"  in url -> "ytmusic"
        else -> "unknown"
    }

    private fun sourceLabel(source: String) = when (source) {
        "spotify"    -> "Spotify"
        "soundcloud" -> "SoundCloud"
        "ytmusic"    -> "YouTube Music"
        else         -> source
    }

}
