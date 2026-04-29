package com.teddybear.aura

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue // Explicitly added to fix KSP delegate errors
import androidx.compose.runtime.setValue // Explicitly added to fix KSP delegate errors
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teddybear.aura.ui.components.MiniPlayer
import com.teddybear.aura.ui.screens.*
import androidx.compose.material.icons.rounded.CheckCircle
import com.teddybear.aura.ui.theme.*

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.initPlayer(application)
        requestStoragePermissions()
        requestBluetoothPermission()
        setContent { MyProgramTheme { AuraApp(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.onAppResume()
    }

    override fun onStop() {
        super.onStop()
    }

    private fun requestBluetoothPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 101)
            }
        }
    }

    private fun requestStoragePermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }
}

// ── Tabs ──────────────────────────────────────────────────────────────────────

enum class AuraTab(val label: String, val icon: ImageVector) {
    HOME      ("Главная",    Icons.Rounded.Home),
    DOWNLOAD  ("Добавить",   Icons.Rounded.Add),
    LIBRARY   ("Библиотека", Icons.AutoMirrored.Rounded.LibraryBooks),
    SETTINGS  ("Настройки",  Icons.Rounded.Settings),
}

// ── Root composable ───────────────────────────────────────────────────────────

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AuraApp(vm: MainViewModel) {
    val currentTrack   by vm.currentTrack.collectAsStateWithLifecycle()
    val isPlaying      by vm.isPlaying.collectAsStateWithLifecycle()
    val position       by vm.position.collectAsStateWithLifecycle()
    val duration       by vm.duration.collectAsStateWithLifecycle()
    val error          by vm.error.collectAsStateWithLifecycle()
    val serverOnline   by vm.serverOnline.collectAsStateWithLifecycle()
    val btPending      by vm.bluetoothPendingDevice.collectAsStateWithLifecycle()
    val proxyRequired  by vm.proxyRequired.collectAsStateWithLifecycle()
    val vpnActive      by vm.vpnActive.collectAsStateWithLifecycle()
    val playlists      by vm.playlists.collectAsStateWithLifecycle()

    // Explicit types added to aid KSP type inference
    var selectedTab: AuraTab by remember { mutableStateOf(AuraTab.HOME) }
    var playerOpen: Boolean by remember { mutableStateOf(false) }
    var showSetupServer: Boolean by remember { mutableStateOf(false) }
    var showYandexLogin: Boolean by remember { mutableStateOf(false) }
    val bannerDismissed by vm.proxyBannerDismissed.collectAsStateWithLifecycle()
    var libraryTapCount: Int by remember { mutableStateOf(0) }
    var lastLibraryTapMs: Long by remember { mutableStateOf(0L) }
    var openPlaylist: PlaylistDestination? by remember { mutableStateOf(null) }
    var showEqFromSettings: Boolean by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { vm.importLocalFile(it) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { vm.importFolder(it) }
    }

    Box(Modifier.fillMaxSize().background(GrooveBg)) {

        Column(Modifier.fillMaxSize()) {

            // ── Screen content ────────────────────────────────────────────────
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        val enter = slideInHorizontally(tween(280)) { it * dir / 5 } + fadeIn(tween(220))
                        val exit  = slideOutHorizontally(tween(200)) { -it * dir / 5 } + fadeOut(tween(160))
                        enter togetherWith exit
                    },
                    label = "tab_anim",
                ) { tab ->
                    when (tab) {
                        AuraTab.HOME      -> HomeScreen(
                            vm = vm,
                            playlists = playlists,
                            onOpenPlaylist = { openPlaylist = it },
                            onImportFolder = { folderPicker.launch(null) },
                            onOpenSettings = { selectedTab = AuraTab.SETTINGS },
                        )
                        AuraTab.DOWNLOAD  -> DownloadScreen(vm)
                        AuraTab.LIBRARY   -> LibraryScreen(vm)
                        AuraTab.SETTINGS  -> SettingsScreen(
                            vm,
                            onSetupServer    = { showSetupServer = true },
                            onYandexLogin    = { showYandexLogin = true },
                            onConfigureEq    = { showEqFromSettings = true },
                        )
                    }
                }
            }

            // ── Proxy warning banner ──────────────────────────────────────────
            AnimatedVisibility(
                visible = proxyRequired && !vpnActive && !bannerDismissed,
                enter   = expandVertically(tween(300)) + fadeIn(),
                exit    = shrinkVertically(tween(250)) + fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GrooveRed.copy(alpha = 0.15f))
                        .border(1.dp, GrooveRed.copy(0.4f), RoundedCornerShape(0.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("🇷🇺", fontSize = 14.sp)
                        Text(
                            "Настройте прокси для загрузки из Spotify, YT и SoundCloud",
                            color = GrooveRed, fontSize = 11.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { selectedTab = AuraTab.SETTINGS },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("Настроить", color = GrooveRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(
                            onClick = { vm.dismissProxyBanner() },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(Icons.Rounded.Close, null, tint = GrooveRed, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // ── Mini player ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible = currentTrack != null,
                enter   = slideInVertically { it } + fadeIn(),
                exit    = slideOutVertically { it } + fadeOut(),
            ) {
                currentTrack?.let { track ->
                    MiniPlayer(
                        track       = track,
                        isPlaying   = isPlaying,
                        posMs       = position,
                        durMs       = duration,
                        onOpen      = { playerOpen = true },
                        onPlayPause = { vm.togglePlayPause() },
                        onNext      = { vm.skipNext() },
                        onPrev      = { vm.skipPrev() },
                    )
                }
            }

            // ── Tab bar ───────────────────────────────────────────────────────
            AuraTabBar(
                selected = selectedTab,
                onSelect = { tab ->
                    var openFilePicker   = false
                    var openFolderPicker = false
                    if (tab == AuraTab.LIBRARY) {
                        val now = System.currentTimeMillis()
                        if (now - lastLibraryTapMs < 600L) {
                            libraryTapCount++
                            when (libraryTapCount) {
                                2 -> openFolderPicker = true
                                3 -> { openFilePicker = true; libraryTapCount = 0 }
                            }
                        } else {
                            libraryTapCount = 1
                        }
                        lastLibraryTapMs = now
                    } else {
                        libraryTapCount  = 0
                        lastLibraryTapMs = 0L
                    }
                    when {
                        openFolderPicker -> { folderPicker.launch(null); libraryTapCount = 0 }
                        openFilePicker   -> filePicker.launch("audio/*")
                        else             -> selectedTab = tab
                    }
                }
            )
        }

        // ── Dialogs & Modals ─────────────────────────────────────────────────
        if (showEqFromSettings) {
            Dialog(
                onDismissRequest = { showEqFromSettings = false },
                properties       = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(GrooveBg)) {
                    com.teddybear.aura.ui.screens.EqualizerScreen(vm = vm, onClose = { showEqFromSettings = false })
                }
            }
        }

        if (showSetupServer) {
            Dialog(
                onDismissRequest = { showSetupServer = false },
                properties       = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(GrooveBg)) {
                    SetupServerScreen(vm = vm, onDone = { showSetupServer = false })
                }
            }
        }

        if (showYandexLogin) {
            Dialog(
                onDismissRequest = { showYandexLogin = false },
                properties       = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(GrooveBg)) {
                    YandexLoginScreen(vm = vm, onBack = { showYandexLogin = false })
                }
            }
        }

        btPending?.let { device ->
            BluetoothDeviceDialog(
                address   = device.address,
                onName    = { label -> vm.nameBluetoothDevice(device, label) },
                onDismiss = { vm.dismissBluetoothDevice() },
            )
        }

        if (playerOpen) {
            Dialog(
                onDismissRequest = { playerOpen = false },
                properties       = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier         = Modifier.fillMaxSize().background(Color.Black.copy(0.55f)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.97f)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .background(GrooveBg),
                    ) {
                        PlayerScreen(vm = vm, onClose = { playerOpen = false })
                    }
                }
            }
        }

        openPlaylist?.let { destination ->
            Dialog(
                onDismissRequest = { openPlaylist = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(GrooveBg)) {
                    PlaylistDetailRoute(
                        vm = vm,
                        destination = destination,
                        onBack = { openPlaylist = null },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible  = error != null,
            enter    = slideInVertically { -it } + fadeIn(),
            exit     = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GrooveRed.copy(0.18f))
                    .border(1.dp, GrooveRed.copy(0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error ?: "", color = GrooveRed, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearError() }) {
                        Text("OK", color = GrooveRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── Tab bar ───────────────────────────────────────────────────────────────────

@Composable
fun AuraTabBar(selected: AuraTab, onSelect: (AuraTab) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF50D0D11)),
    ) {
        HorizontalDivider(color = GrooveBorder, modifier = Modifier.align(Alignment.TopCenter))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(62.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            AuraTab.entries.forEach { tab ->
                val active = tab == selected

                if (tab == AuraTab.DOWNLOAD) {
                    Box(
                        modifier         = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .shadow(
                                    elevation    = 12.dp,
                                    shape        = RoundedCornerShape(16.dp),
                                    ambientColor = GroovePurple.copy(0.6f),
                                    spotColor    = GroovePurple.copy(0.8f),
                                )
                                .background(GroovePurple, RoundedCornerShape(16.dp))
                                .clickable { onSelect(tab) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                tint     = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                } else {
                    // Explicit targetValue & generic types added here to fix KSP inference
                    val iconScale: Float by animateFloatAsState(
                        targetValue = if (active) 1.15f else 1f,
                        animationSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow),
                        label = "tabScale",
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelect(tab) }
                            .padding(bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint     = if (active) GroovePurple else GrooveText3,
                            modifier = Modifier.size((20 * iconScale).dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            color      = if (active) GroovePurple else GrooveText3,
                            fontSize   = 9.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        AnimatedVisibility(active) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(4.dp)
                                    .background(GroovePurple, RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Bluetooth device naming dialog ────────────────────────────────────────────

@Composable
@Suppress("UNUSED_PARAMETER")
fun BluetoothDeviceDialog(
    address: String,
    onName: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf("Наушники", "Колонка", "Авто", "Гарнитура", "Другое")

    // Explicit types added
    var custom: String by remember { mutableStateOf("") }
    var showCustom: Boolean by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = com.teddybear.aura.ui.theme.GrooveBg2,
        title = {
            Text("Новое устройство", color = com.teddybear.aura.ui.theme.GrooveText,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Что подключилось? Приложение запомнит настройки EQ для этого типа.",
                    color = com.teddybear.aura.ui.theme.GrooveText2, fontSize = 13.sp)
                options.forEach { opt ->
                    TextButton(
                        onClick = {
                            if (opt == "Другое") showCustom = true
                            else onName(opt)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(opt, color = com.teddybear.aura.ui.theme.GroovePurple,
                            fontSize = 14.sp)
                    }
                }
                if (showCustom) {
                    OutlinedTextField(
                        value = custom, onValueChange = { custom = it },
                        label = { Text("Название", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = com.teddybear.aura.ui.theme.GroovePurple,
                            unfocusedBorderColor = com.teddybear.aura.ui.theme.GrooveBorder,
                            focusedTextColor = com.teddybear.aura.ui.theme.GrooveText,
                            unfocusedTextColor = com.teddybear.aura.ui.theme.GrooveText,
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    )
                    Button(
                        onClick = { if (custom.isNotBlank()) onName(custom.trim()) },
                        enabled = custom.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = com.teddybear.aura.ui.theme.GroovePurple),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Пропустить", color = com.teddybear.aura.ui.theme.GrooveText3)
            }
        }
    )
}
