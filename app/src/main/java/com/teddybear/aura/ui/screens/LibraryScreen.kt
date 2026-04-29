package com.teddybear.aura.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.data.PlaylistWithCount
import com.teddybear.aura.data.Track
import com.teddybear.aura.ui.theme.*

enum class LibraryFilter { ALL, ARTISTS, ALBUMS, SOURCE }
enum class LibrarySort   { NAME, DATE, DURATION, PLAYS }

@Composable
fun LibraryScreen(vm: MainViewModel) {
    val tracks        by vm.tracks.collectAsStateWithLifecycle()
    val stats         by vm.stats.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val playlists     by vm.playlists.collectAsStateWithLifecycle()
    val currentTrack  by vm.currentTrack.collectAsStateWithLifecycle()
    val focusMgr = LocalFocusManager.current

    var searchQuery  by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var activeSort   by remember { mutableStateOf(LibrarySort.NAME) }
    var sortExpanded by remember { mutableStateOf(false) }
    var menuTrackId  by remember { mutableStateOf<Int?>(null) }
    var addToPlaylistTrackId by remember { mutableStateOf<Int?>(null) }

    val sorted = remember(tracks, activeSort) {
        when (activeSort) {
            LibrarySort.NAME     -> tracks.sortedBy { it.title.lowercase() }
            LibrarySort.DATE     -> tracks.sortedByDescending { it.id }
            LibrarySort.DURATION -> tracks.sortedByDescending { it.durationSec ?: 0.0 }
            LibrarySort.PLAYS    -> tracks.sortedByDescending { it.playCount }
        }
    }

    val displayTracks = when {
        searchQuery.isNotEmpty()              -> searchResults
        activeFilter == LibraryFilter.ARTISTS -> sorted.sortedBy { it.artist.lowercase() }
        activeFilter == LibraryFilter.ALBUMS  -> sorted.sortedBy { it.album.lowercase() }
        activeFilter == LibraryFilter.SOURCE  -> sorted.sortedBy { it.source }
        else -> sorted
    }

    LazyColumn(
        modifier       = Modifier.fillMaxSize().background(GrooveBg),
        contentPadding = PaddingValues(bottom = 130.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 8.dp)) {
                Text("Библиотека", color = GrooveText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                stats?.let { s ->
                    val albumCount  = tracks.map { it.album }.filter { it.isNotBlank() }.distinct().size
                    val artistCount = tracks.map { it.artist }.distinct().size
                    Text("${s.trackCount} треков · $albumCount альбома · $artistCount исполнителей",
                        color = GrooveText3, fontSize = 12.sp)
                }
            }
        }

        // Search bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp)).background(GrooveBg2)
                    .border(1.dp, GrooveBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = GrooveText3, modifier = Modifier.size(18.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; vm.search(it) },
                    placeholder = { Text("Поиск в библиотеке...", color = GrooveText3, fontSize = 13.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = GrooveText, unfocusedTextColor = GrooveText,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusMgr.clearFocus() }),
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = ""; vm.search("") }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.Clear, null, tint = GrooveText3, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Filter chips + sort
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LibraryFilter.entries) { filter ->
                        val label = when (filter) {
                            LibraryFilter.ALL     -> "Все"
                            LibraryFilter.ARTISTS -> "Исполнители"
                            LibraryFilter.ALBUMS  -> "Альбомы"
                            LibraryFilter.SOURCE  -> "Источник"
                        }
                        val active = filter == activeFilter
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (active) GroovePurple else GrooveBg2)
                                .border(1.dp, if (active) GroovePurple else GrooveBorder, RoundedCornerShape(50))
                                .clickable { activeFilter = filter }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(label, color = if (active) Color.White else GrooveText3, fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Box {
                    IconButton(onClick = { sortExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Sort, null, tint = GrooveText3, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false },
                        modifier = Modifier.background(GrooveBg2)) {
                        listOf(
                            LibrarySort.NAME     to "По названию",
                            LibrarySort.DATE     to "По дате добавления",
                            LibrarySort.DURATION to "По длительности",
                            LibrarySort.PLAYS    to "По прослушиваниям",
                        ).forEach { (sort, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = if (sort == activeSort) GroovePurple else GrooveText, fontSize = 13.sp) },
                                onClick = { activeSort = sort; sortExpanded = false },
                                leadingIcon = if (sort == activeSort) ({ Icon(Icons.Rounded.Check, null, tint = GroovePurple, modifier = Modifier.size(16.dp)) }) else null,
                            )
                        }
                    }
                }
            }
        }

        // Grouped by artist
        if (activeFilter == LibraryFilter.ARTISTS && searchQuery.isEmpty()) {
            val byArtist = displayTracks.groupBy { it.artist }
            byArtist.forEach { (artist, artistTracks) ->
                stickyHeader(key = "art_$artist") {
                    Box(Modifier.fillMaxWidth().background(GrooveBg).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(artist.ifBlank { "Неизвестный исполнитель" },
                            color = GrooveText2, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }
                itemsIndexed(artistTracks, key = { _, t -> "a_${t.id}" }) { idx, track ->
                    TrackRowLibrary(idx + 1, track, currentTrack?.id, { vm.playTrack(track, artistTracks) },
                        { menuTrackId = if (menuTrackId == track.id) null else track.id }, menuTrackId == track.id,
                        { vm.deleteTrack(track.id, deleteFile = true); menuTrackId = null },
                        { addToPlaylistTrackId = track.id; menuTrackId = null })
                }
            }
        } else if (activeFilter == LibraryFilter.SOURCE && searchQuery.isEmpty()) {
            val bySource = displayTracks.groupBy { it.source }
            bySource.forEach { (source, sourceTracks) ->
                stickyHeader(key = "src_$source") {
                    Box(Modifier.fillMaxWidth().background(GrooveBg).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(libSourceLabel(source), color = libSourceColor(source),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }
                itemsIndexed(sourceTracks, key = { _, t -> "s_${t.id}" }) { idx, track ->
                    TrackRowLibrary(idx + 1, track, currentTrack?.id, { vm.playTrack(track, sourceTracks) },
                        { menuTrackId = if (menuTrackId == track.id) null else track.id }, menuTrackId == track.id,
                        { vm.deleteTrack(track.id, deleteFile = true); menuTrackId = null },
                        { addToPlaylistTrackId = track.id; menuTrackId = null })
                }
            }
        } else {
            itemsIndexed(displayTracks, key = { _, track -> track.id }) { index, track ->
                TrackRowLibrary(index + 1, track, currentTrack?.id, { vm.playTrack(track, displayTracks) },
                    { menuTrackId = if (menuTrackId == track.id) null else track.id }, menuTrackId == track.id,
                    { vm.deleteTrack(track.id, deleteFile = true); menuTrackId = null },
                    { addToPlaylistTrackId = track.id; menuTrackId = null })
            }
        }

        if (displayTracks.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎵", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(if (searchQuery.isNotEmpty()) "Ничего не найдено" else "Библиотека пуста",
                            color = GrooveText3, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AlertDialog(
            onDismissRequest = { addToPlaylistTrackId = null },
            containerColor   = GrooveBg2,
            title = { Text("Добавить в плейлист", color = GrooveText, fontWeight = FontWeight.Bold) },
            text = {
                if (playlists.isEmpty()) {
                    Text("У вас нет плейлистов.", color = GrooveText2, fontSize = 13.sp)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(playlists, key = { _, p -> p.playlist.id }) { _, pwc ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { vm.addTrackToPlaylist(pwc.playlist.id, trackId); addToPlaylistTrackId = null }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                                    .background(Brush.linearGradient(listOf(GroovePurple, GroovePurple2))),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.QueueMusic, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(pwc.playlist.name, color = GrooveText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("${pwc.trackCount} треков", color = GrooveText3, fontSize = 11.sp)
                                }
                            }
                            HorizontalDivider(color = GrooveBorder)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { addToPlaylistTrackId = null }) { Text("Отмена", color = GrooveText3) } },
        )
    }
}

// ── Mini waveform bars (now-playing indicator) ───────────────────────────────

@Composable
private fun MiniWaveformBars() {
    val tr = rememberInfiniteTransition(label = "wf")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(16.dp)) {
        listOf(0.5f, 1f, 0.65f, 0.85f, 0.4f).forEachIndexed { i, base ->
            val h by tr.animateFloat(
                initialValue = base * 5f, targetValue = base * 14f,
                animationSpec = infiniteRepeatable(tween(380 + i * 70, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "b$i",
            )
            Box(Modifier.width(2.dp).height(h.dp).background(GroovePurple, RoundedCornerShape(1.dp)))
        }
    }
}

// ── Library track row ────────────────────────────────────────────────────────

@Composable
private fun TrackRowLibrary(
    index: Int,
    track: Track,
    currentTrackId: Int?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onDelete: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val isPlaying = track.id == currentTrackId
    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (isPlaying) GroovePurple.copy(0.07f) else Color.Transparent)
                .clickable { onClick() }
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(index.toString(), color = if (isPlaying) GroovePurple else GrooveText3,
                fontSize = 12.sp, modifier = Modifier.width(22.dp))

            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
                if (track.coverPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file://${track.coverPath}").crossfade(true).build(),
                        contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.linearGradient(listOf(Color(track.sourceColor), Color(track.sourceColor).copy(0.5f)))
                        ), contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(18.dp))
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        track.title,
                        color = if (isPlaying) GroovePurple else GrooveText,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (track.rating >= 4f) {
                        Icon(Icons.Rounded.Favorite, null, tint = GroovePurple, modifier = Modifier.size(12.dp))
                    }
                }
                val sub = buildString {
                    append(track.artist)
                    if (track.album.isNotBlank()) append(" · ${track.album}")
                }
                Text(sub, color = GrooveText3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (isPlaying) MiniWaveformBars()
            else if (track.durationFormatted.isNotEmpty()) Text(track.durationFormatted, color = GrooveText3, fontSize = 11.sp)

            IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.MoreVert, null, tint = GrooveText3, modifier = Modifier.size(18.dp))
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenu, modifier = Modifier.background(GrooveBg2)) {
            DropdownMenuItem(
                text = { Text("Воспроизвести", color = GrooveText, fontSize = 13.sp) },
                onClick = { onClick(); onMenu() },
                leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, tint = GroovePurple, modifier = Modifier.size(18.dp)) },
            )
            DropdownMenuItem(
                text = { Text("В плейлист", color = GrooveText, fontSize = 13.sp) },
                onClick = { onAddToPlaylist(); onMenu() },
                leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null, tint = GroovePurple, modifier = Modifier.size(18.dp)) },
            )
            HorizontalDivider(color = GrooveBorder)
            DropdownMenuItem(
                text = { Text("Удалить", color = GrooveRed, fontSize = 13.sp) },
                onClick = onDelete,
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = GrooveRed, modifier = Modifier.size(18.dp)) },
            )
        }
    }
    HorizontalDivider(color = GrooveBorder, modifier = Modifier.padding(start = 80.dp))
}

// ── Shared TrackRow (used by HomeScreen) ──────────────────────────────────────

@Composable
fun TrackRow(track: Track, showDuration: Boolean = false, iconSize: Dp = 46.dp, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(iconSize).clip(RoundedCornerShape(8.dp))) {
            if (track.coverPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("file://${track.coverPath}").crossfade(true).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(track.sourceColor), Color(track.sourceColor).copy(0.5f)))
                    ), contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(iconSize / 2.5f))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, color = GrooveText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = GrooveText2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showDuration && track.durationFormatted.isNotEmpty()) {
            Text(track.durationFormatted, color = GrooveText3, fontSize = 11.sp)
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, color = GrooveText3, fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
    )
}

private fun libSourceLabel(s: String) = when (s) {
    "yandex" -> "ЯНДЕКС МУЗЫКА"; "spotify" -> "SPOTIFY"
    "soundcloud" -> "SOUNDCLOUD"; "ytmusic" -> "YT MUSIC"; "local" -> "ЛОКАЛЬНЫЕ"; else -> s.uppercase()
}
private fun libSourceColor(s: String) = when (s) {
    "yandex" -> SourceColorYandex; "spotify" -> SourceColorSpotify
    "soundcloud" -> SoundcloudOrg; "ytmusic" -> YTMusicRed; else -> GrooveText2
}
