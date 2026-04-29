# AURA — Android Music Player

> **Version:** beta 0.7.0.a  
> **Platform:** Android  
> **Author:** [Teddy Bear](https://t.me/fuf_bear)  
> **License:** Proprietary. All rights reserved.  
> 🇷🇺 [Русская версия](./README_RU.md)

---

## About

**AURA** is a private Android music player built for people who want to listen to music without subscriptions or restrictions. The app lets you download tracks from Yandex Music, Spotify, SoundCloud, and YouTube Music directly to your device, manage a local library, create playlists, and fine-tune the sound with a built-in equalizer.

AURA is written in Kotlin with Jetpack Compose and ExoPlayer.

---

## Features

### 🎵 Player
- Full-screen player with album art, animations, and gesture controls
- Swipe left/right to skip tracks
- Repeat modes: off / all tracks / single track
- Smart shuffle weighted by listening history
- Sleep timer — automatically stops playback after a set time
- Mini-player always accessible at the bottom of the screen
- Background playback via a foreground media service
- Playback position is saved on exit and restored on the next launch

### 📥 Downloading Music
Paste a link to a track or playlist — AURA detects the source automatically and starts the download.

| Source | Tracks | Playlists |
|---|---|---|
| **Yandex Music** | ✅ | ✅ |
| **Spotify** | ✅ (via home server) | ✅ (via home server) |
| **SoundCloud** | ✅ (via home server) | ✅ (via home server) |
| **YouTube Music** | ✅ (via home server) | ✅ (via home server) |

- Real-time download progress
- Job statuses: queued → downloading → tagging → done / error / cancelled
- Automatic loudness normalization (ReplayGain via ffmpeg)
- Quality selection: 32 to 320 kbps, or auto (best available)
- Files are saved to external storage under `Android/data/.../Music/<source>/`

### 📚 Library
- Automatic scanning and indexing of downloaded tracks
- Full metadata support: title, artist, album, year, genre, track number, duration
- Album art is extracted and cached locally
- ID3 / Vorbis Comment tag support
- Import a folder of local audio files directly from the device
- SHA-256 deduplication — the same track will never be added twice
- Library stats: track count, total size (MB), total duration (hours)

### 📋 Playlists
**User playlists** — create, rename, delete, add tracks, and reorder them freely.

**Smart (dynamic) playlists** — built automatically from your listening stats:

| Playlist | Description |
|---|---|
| **Library** (renameable) | All tracks |
| **I Listen Often** (renameable) | Tracks with a high play count |
| **Rarely Played** (renameable) | Tracks you almost never listen to |
| **Favorites** | Tracks you marked as favorite |
| **Rated** | Tracks you have given a rating |
| **Recently Added** | Latest downloads |
| **Recently Played** | Sorted by last playback date |

Smart playlist names can be changed in Settings.

### 🎛 Equalizer
- 5-band parametric equalizer (60 Hz, 230 Hz, 910 Hz, 3 kHz, 14 kHz)
- Range: −15 dB to +15 dB per band
- Bass Boost: 0–1000
- Virtualizer (surround effect): 0–1000
- 10 built-in presets:

| Preset | Character |
|---|---|
| Flat | No changes |
| Bass Boost | Enhanced low end |
| Tracks | Gentle pop-friendly boost |
| Rock | Strong lows and highs |
| Pop | Emphasized mids |
| Jazz | Warm and detailed |
| Hip-Hop | Bass-forward |
| Electronic | Dynamic, wide sound |
| Classical | Spacious and airy |
| Vocal | Highlighted vocals |

- EQ settings persist across restarts
- Applied in real time via Android AudioEffect API

### 🎧 Bluetooth Profiles
- AURA remembers the EQ settings used with each Bluetooth device
- When headphones or a speaker reconnect, EQ is applied automatically
- Profile collection can be disabled in Settings

### 🖥 Home Server
Downloading from Spotify, SoundCloud, and YouTube Music requires a personal server running `yt-dlp` / `spotdl`. AURA connects to it over HTTP.

- Server online/offline status is visible at all times in Settings
- One-tap setup and connection check

### 🎤 Lyrics
- Embedded plain-text lyrics
- Synchronized LRC lyrics — words are highlighted in time with the music
- Displayed inside the player screen — swipe up to reveal

### 📊 Listening Stats
- Play counter per track
- Last played timestamp
- User star rating per track
- Stats power smart playlists and the smart shuffle algorithm
- Stats can be fully reset (double confirmation required)
- Stat collection can be disabled entirely in Settings

---

## Installation

1. Download the APK from the official source (author's channel)
2. On your device, allow installation from unknown sources:  
   **Settings → Security → Install unknown apps**
3. Install the APK
4. On first launch, grant storage permissions so AURA can save music

**Minimum Android version:** 10 (API 29)  
**Recommended:** Android 12+

---

## Getting Started

### Step 1 — Log in to Yandex Music
Yandex Music authentication is required to download full tracks (without it, Yandex serves only 30-second previews):

1. Open **Settings** (gear icon on the home screen)
2. Tap **"Sign in to Yandex Music"**
3. Log in with your Yandex account in the browser that opens
4. Your username will appear in Settings once the session is active

### Step 2 — Set Up the Home Server (for Spotify / SoundCloud / YouTube)
1. Deploy a server with `yt-dlp` / `spotdl` on any VPS or home machine
2. In **Settings**, tap **"Set up"** under **Home Server**
3. Enter your server URL
4. Wait for the **"Server connected"** status indicator to turn green

### Step 3 — Add Music
**Option A — Download from the internet:**
1. Go to the **"Add Music"** tab
2. Paste a track or playlist link
3. Tap the download button
4. Monitor progress in the job list below

**Option B — Import local files:**
1. On the home screen, tap **"Import folder"**
2. Select a folder containing audio files on your device
3. Tracks will appear in the library automatically

---

## App Navigation

| Section | Description |
|---|---|
| 🏠 **Home** | Recent tracks, smart playlists, library stats |
| ❤️ **Library** | All tracks, search, sort |
| ➕ **Add** | Download by link, download queue |
| 📋 **Playlists** | User and smart playlists |
| ⚙️ **Settings** | Accounts, equalizer, audio quality, stats |

---

## Audio Settings

**Download quality:**
- Auto (best available)
- 320 / 256 / 192 / 128 / 96 / 64 / 48 / 32 kbps

**Volume normalization:**
- When enabled, all tracks play at a consistent perceived loudness level using ReplayGain analysis (applied via ffmpeg at download time)

---

## Permissions

| Permission | Purpose |
|---|---|
| Internet | Downloading tracks, connecting to services |
| Storage | Saving and reading audio files |
| Foreground service | Background playback |
| Bluetooth | Device profiles, automatic EQ switching |
| Camera | QR scanner for quick configuration |

---

## FAQ

**Why does Yandex Music only download 30 seconds?**  
You are not signed in, or the session has expired. Go to **Settings → Sign in to Yandex Music** and log in again.

**Why are "I Listen Often" and "Rarely Played" empty?**  
These playlists are built from listening stats. Make sure **"Track listening stats"** is enabled in Settings.

**Why won't Spotify / SoundCloud download?**  
These sources require a configured home server. Check the status in **Settings → Home Server**.

**Tracks don't appear in the library after downloading.**  
The download is likely still in progress. Open the **"Add Music"** tab and check the job status.

**How do I rename a smart playlist?**  
Long-press the playlist → **Rename**.

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI framework | Jetpack Compose |
| Player | ExoPlayer (Media3) |
| Database | Room |
| Preferences | DataStore |
| Networking | OkHttp |
| Image loading | Coil |
| Background tasks | WorkManager |

---

## Contact & Support

For questions and feedback, reach out to the author: **[@fuf_bear](https://t.me/fuf_bear)**

---

> © 2026 Teddy Bear. Proprietary software. Copying, distribution, and modification are strictly prohibited without the written permission of the rights holder.
