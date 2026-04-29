package com.teddybear.aura.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.data.PlaylistWithCount
import com.teddybear.aura.data.Track
import com.teddybear.aura.ui.theme.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    playlists: List<PlaylistWithCount>,
    onOpenPlaylist: (PlaylistDestination) -> Unit,
    onImportFolder: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val tracks        by vm.tracks.collectAsStateWithLifecycle()
    val recent        by vm.recentTracks.collectAsStateWithLifecycle()
    val recentPlayed  by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val stats         by vm.stats.collectAsStateWithLifecycle()
    val frequentTracks by vm.frequentTracks.collectAsStateWithLifecycle()
    val rareTracks    by vm.rareTracks.collectAsStateWithLifecycle()
    val favoriteTracks by vm.favoriteTracks.collectAsStateWithLifecycle()
    val ratedTracks by vm.ratedTracks.collectAsStateWithLifecycle()
    val dynNameAll    by vm.dynNameAll.collectAsStateWithLifecycle()
    val dynNameFreq   by vm.dynNameFrequent.collectAsStateWithLifecycle()
    val dynNameRare   by vm.dynNameRare.collectAsStateWithLifecycle()
    val autoPlaylists = remember(
        tracks,
        frequentTracks,
        rareTracks,
        favoriteTracks,
        ratedTracks,
        dynNameAll,
        dynNameFreq,
        dynNameRare,
    ) {
        buildAutoPlaylists(
            allTracks = tracks,
            frequentTracks = frequentTracks,
            rareTracks = rareTracks,
            favoriteTracks = favoriteTracks,
            ratedTracks = ratedTracks,
            dynNameAll = dynNameAll,
            dynNameFreq = dynNameFreq,
            dynNameRare = dynNameRare,
        )
    }

    val greeting = run {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            h < 6  -> "Доброй ночи"
            h < 12 -> "Доброе утро"
            h < 18 -> "Добрый день"
            else   -> "Добрый вечер"
        }
    }
    val dateStr = SimpleDateFormat("d MMMM", Locale("ru")).format(Date())

    LazyColumn(
        modifier       = Modifier.fillMaxSize().background(GrooveBg),
        contentPadding = PaddingValues(bottom = 130.dp),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    // FIX #7: emoji is included per design (already had 👋, keeping it)
                    Text(
                        "$greeting 👋",
                        color = GrooveText, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$dateStr · ${stats?.trackCount ?: 0} треков в библиотеке",
                        color = GrooveText2, fontSize = 13.sp,
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = GrooveText2,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // ── Empty state ───────────────────────────────────────────────────────
        if (tracks.isEmpty() && recent.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("🎵", fontSize = 56.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Библиотека пуста", color = GrooveText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Перейди во вкладку «Добавить» и вставь ссылку\n" +
                        "на трек с Яндекс Музыки, Spotify, SoundCloud\n" +
                        "или YouTube Music",
                        color = GrooveText2, fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (autoPlaylists.isNotEmpty() || playlists.isNotEmpty()) {
            item {
                SectionLabel("ПЛЕЙЛИСТЫ")
                PlaylistCarousel(
                    playlists = playlists,
                    autoPlaylists = autoPlaylists,
                    onOpenPlaylist = onOpenPlaylist,
                )
            }
        }

        // ── Recently played ───────────────────────────────────────────────────
        if (recentPlayed.isNotEmpty()) {
            item { SectionLabel("НЕДАВНО СЛУШАЛИ") }
            itemsIndexed(recentPlayed.take(10), key = { _, t -> t.id }) { _, track ->
                TrackRow(track = track, showDuration = true, iconSize = 92.dp, onClick = { vm.playTrack(track, recentPlayed) })
            }
        }

        if (tracks.isNotEmpty() && recentPlayed.isEmpty()) {
            item { SectionLabel("ВСЕ ТРЕКИ") }
            itemsIndexed(tracks, key = { _, t -> t.id }) { _, track ->
                TrackRow(track, showDuration = true, onClick = { vm.playTrack(track, tracks) })
            }
        }
    }
}

// ── Album card — FIX #2: removed stagger LaunchedEffect delay ────────────────

@Composable
fun AlbumCard(track: Track, size: Dp, onClick: () -> Unit) {
    // Simple fade-in on first composition — no stagger delay
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn(tween(200)),
    ) {
        Column(modifier = Modifier.width(size).clickable { onClick() }) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(14.dp))
                    .shadow(8.dp, RoundedCornerShape(14.dp)),
            ) {
                if (track.coverPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data("file://${track.coverPath}").crossfade(true).build(),
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(track.sourceColor), Color(track.sourceColor).copy(0.5f))
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(36.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(track.title, color = GrooveText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = GrooveText2, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Dynamic playlist card ──────────────────────────────────────────────────────

@Composable
fun DynamicPlaylistCard(
    name: String,
    count: Int,
    gradient: List<Color>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(150.dp).clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(colors = gradient)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.LibraryMusic, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("$count треков", color = Color.White.copy(0.8f), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = GrooveText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
