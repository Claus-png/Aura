package com.teddybear.aura.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FiberNew
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.teddybear.aura.MainViewModel
import com.teddybear.aura.data.PlaylistWithCount
import com.teddybear.aura.data.Track
import com.teddybear.aura.ui.theme.GrooveBg
import com.teddybear.aura.ui.theme.GrooveBg2
import com.teddybear.aura.ui.theme.GrooveBorder
import com.teddybear.aura.ui.theme.GroovePurple
import com.teddybear.aura.ui.theme.GroovePurple2
import com.teddybear.aura.ui.theme.GrooveText
import com.teddybear.aura.ui.theme.GrooveText2
import com.teddybear.aura.ui.theme.GrooveText3

data class AutoPlaylistSpec(
    val key: String,
    val name: String,
    val subtitle: String,
    val gradient: List<Color>,
    val icon: ImageVector,
    val tracks: List<Track>,
)

sealed interface PlaylistDestination {
    data class User(val playlist: PlaylistWithCount) : PlaylistDestination
    data class Auto(val playlist: AutoPlaylistSpec) : PlaylistDestination
}

fun buildAutoPlaylists(
    allTracks: List<Track>,
    frequentTracks: List<Track>,
    rareTracks: List<Track>,
    favoriteTracks: List<Track>,
    ratedTracks: List<Track>,
    dynNameAll: String,
    dynNameFreq: String,
    dynNameRare: String,
): List<AutoPlaylistSpec> = listOf(
    AutoPlaylistSpec(
        key = "recent",
        name = "Недавно добавленные",
        subtitle = "Последние импортированные треки",
        gradient = listOf(Color(0xFF1E40AF), Color(0xFF3B82F6)),
        icon = Icons.Rounded.FiberNew,
        tracks = allTracks.sortedByDescending { it.addedAt },
    ),
    AutoPlaylistSpec(
        key = "favorites",
        name = "⭐ Любимые",
        subtitle = "Треки с рейтингом 4.0 и выше",
        gradient = listOf(Color(0xFFB91C1C), Color(0xFFEF4444)),
        icon = Icons.Rounded.Favorite,
        tracks = favoriteTracks,
    ),
    AutoPlaylistSpec(
        key = "rating",
        name = "📊 По рейтингу",
        subtitle = "Сортировка по пользовательской оценке",
        gradient = listOf(Color(0xFF92400E), Color(0xFFF59E0B)),
        icon = Icons.Rounded.Equalizer,
        tracks = ratedTracks,
    ),
    AutoPlaylistSpec(
        key = "frequent",
        name = dynNameFreq,
        subtitle = "Часто прослушиваемые",
        gradient = listOf(Color(0xFFf093fb), Color(0xFFf5576c)),
        icon = Icons.Rounded.Repeat,
        tracks = frequentTracks,
    ),
    AutoPlaylistSpec(
        key = "rare",
        name = dynNameRare,
        subtitle = "Редко прослушиваемые",
        gradient = listOf(Color(0xFF667eea), Color(0xFF764ba2)),
        icon = Icons.Rounded.Star,
        tracks = rareTracks,
    ),
    AutoPlaylistSpec(
        key = "all",
        name = dynNameAll,
        subtitle = "Вся библиотека",
        gradient = listOf(Color(0xFFfa709a), Color(0xFFfee140)),
        icon = Icons.Rounded.LibraryMusic,
        tracks = allTracks,
    ),
)

@Composable
fun PlaylistsScreen(
    vm: MainViewModel,
    onOpenPlaylist: (PlaylistDestination) -> Unit = {},
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val allTracks by vm.tracks.collectAsStateWithLifecycle()
    val frequentTracks by vm.frequentTracks.collectAsStateWithLifecycle()
    val rareTracks by vm.rareTracks.collectAsStateWithLifecycle()
    val favoriteTracks by vm.favoriteTracks.collectAsStateWithLifecycle()
    val ratedTracks by vm.ratedTracks.collectAsStateWithLifecycle()
    val dynNameAll by vm.dynNameAll.collectAsStateWithLifecycle()
    val dynNameFreq by vm.dynNameFrequent.collectAsStateWithLifecycle()
    val dynNameRare by vm.dynNameRare.collectAsStateWithLifecycle()

    val autoPlaylists = remember(
        allTracks,
        frequentTracks,
        rareTracks,
        favoriteTracks,
        ratedTracks,
        dynNameAll,
        dynNameFreq,
        dynNameRare,
    ) {
        buildAutoPlaylists(
            allTracks = allTracks,
            frequentTracks = frequentTracks,
            rareTracks = rareTracks,
            favoriteTracks = favoriteTracks,
            ratedTracks = ratedTracks,
            dynNameAll = dynNameAll,
            dynNameFreq = dynNameFreq,
            dynNameRare = dynNameRare,
        )
    }

    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(GrooveBg),
        contentPadding = PaddingValues(bottom = 130.dp),
    ) {
        item {
            Text(
                "Плейлисты",
                color = GrooveText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 16.dp),
            )
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, GrooveBorder, RoundedCornerShape(12.dp))
                    .clickable { showCreate = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, tint = GrooveText3, modifier = Modifier.size(18.dp))
                    Text("Создать плейлист", color = GrooveText3, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (playlists.isNotEmpty()) {
            item { SectionLabel("МОИ ПЛЕЙЛИСТЫ") }
            items(playlists, key = { it.playlist.id }) { playlist ->
                PlaylistRow(
                    name = playlist.playlist.name,
                    subtitle = "${playlist.trackCount} треков",
                    gradient = listOf(GroovePurple, GroovePurple2),
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    onClick = { onOpenPlaylist(PlaylistDestination.User(playlist)) },
                    onLongClick = { vm.deletePlaylist(playlist.playlist) },
                )
            }
        }

        item { SectionLabel("АВТОМАТИЧЕСКИЕ") }
        items(autoPlaylists, key = { it.key }) { playlist ->
            PlaylistRow(
                name = playlist.name,
                subtitle = "${playlist.tracks.size} треков · ${playlist.subtitle}",
                gradient = playlist.gradient,
                icon = playlist.icon,
                onClick = { onOpenPlaylist(PlaylistDestination.Auto(playlist)) },
            )
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false; newName = "" },
            containerColor = GrooveBg2,
            title = { Text("Новый плейлист", color = GrooveText, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Название", color = GrooveText3) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GroovePurple,
                        unfocusedBorderColor = GrooveBorder,
                        focusedTextColor = GrooveText,
                        unfocusedTextColor = GrooveText,
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        vm.createPlaylist(newName.trim())
                        newName = ""
                        showCreate = false
                    }
                }) {
                    Text("Создать", color = GroovePurple, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false; newName = "" }) {
                    Text("Отмена", color = GrooveText3)
                }
            },
        )
    }
}

@Composable
fun PlaylistCarousel(
    playlists: List<PlaylistWithCount>,
    autoPlaylists: List<AutoPlaylistSpec>,
    onOpenPlaylist: (PlaylistDestination) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(autoPlaylists, key = { it.key }) { playlist ->
            PlaylistCard(
                title = playlist.name,
                subtitle = "${playlist.tracks.size} треков",
                gradient = playlist.gradient,
                icon = playlist.icon,
                onClick = { onOpenPlaylist(PlaylistDestination.Auto(playlist)) },
            )
        }
        items(playlists, key = { it.playlist.id }) { playlist ->
            PlaylistCard(
                title = playlist.playlist.name,
                subtitle = "${playlist.trackCount} треков",
                gradient = listOf(GroovePurple, GroovePurple2),
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                onClick = { onOpenPlaylist(PlaylistDestination.User(playlist)) },
            )
        }
    }
}

@Composable
fun PlaylistDetailRoute(
    vm: MainViewModel,
    destination: PlaylistDestination,
    onBack: () -> Unit,
) {
    val playlistTracks by vm.playlistTracks.collectAsStateWithLifecycle()
    LaunchedEffect(destination) {
        if (destination is PlaylistDestination.User) {
            vm.loadPlaylistTracks(destination.playlist.playlist.id)
        }
    }
    val title = when (destination) {
        is PlaylistDestination.User -> destination.playlist.playlist.name
        is PlaylistDestination.Auto -> destination.playlist.name
    }
    val tracks = when (destination) {
        is PlaylistDestination.User -> playlistTracks
        is PlaylistDestination.Auto -> destination.playlist.tracks
    }
    PlaylistDetailScreen(
        title = title,
        tracks = tracks,
        onBack = onBack,
        onPlay = { track -> vm.playTrack(track, tracks) },
        onRemove = if (destination is PlaylistDestination.User) {
            { track -> vm.removeTrackFromPlaylist(destination.playlist.playlist.id, track.id) }
        } else {
            null
        },
    )
}

@Composable
private fun PlaylistCard(
    title: String,
    subtitle: String,
    gradient: List<Color>,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(160.dp).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(colors = gradient)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(10.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            color = GrooveText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PlaylistDetailScreen(
    title: String,
    tracks: List<Track>,
    onBack: () -> Unit,
    onPlay: (Track) -> Unit,
    onRemove: ((Track) -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(GrooveBg),
        contentPadding = PaddingValues(bottom = 130.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 20.dp, top = 52.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = GrooveText,
                    )
                }
                Text(
                    title,
                    color = GrooveText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${tracks.size} треков", color = GrooveText3, fontSize = 12.sp)
            }
        }

        if (tracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎵", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Плейлист пуст", color = GrooveText3, fontSize = 14.sp)
                    }
                }
            }
        } else {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                PlaylistTrackRow(
                    index = index + 1,
                    track = track,
                    onClick = { onPlay(track) },
                    onRemove = onRemove?.let { { it(track) } },
                )
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    index: Int,
    track: Track,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            index.toString(),
            color = GrooveText3,
            fontSize = 12.sp,
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.End,
        )

        Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) {
            if (track.coverPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("file://${track.coverPath}")
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(track.sourceColor), Color(track.sourceColor).copy(alpha = 0.5f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Text(track.title, color = GrooveText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                color = GrooveText2,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(track.durationFormatted, color = GrooveText3, fontSize = 11.sp)

        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.RemoveCircleOutline, null, tint = GrooveText3, modifier = Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider(color = GrooveBorder, modifier = Modifier.padding(start = 82.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    name: String,
    subtitle: String,
    gradient: List<Color>,
    icon: ImageVector,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(colors = gradient)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = GrooveText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = GrooveText3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = GrooveText3, modifier = Modifier.size(18.dp))
    }
    HorizontalDivider(color = GrooveBorder, modifier = Modifier.padding(start = 82.dp))
}
