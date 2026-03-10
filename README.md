# NekoVideo

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blueviolet?logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-11%2B-green?logo=android)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-latest-blue?logo=android)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/Media3%2FExoPlayer-latest-orange?logo=android)](https://developer.android.com/guide/topics/media/media3)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![F-Droid](https://img.shields.io/badge/F--Droid-Pending-lightgrey?logo=f-droid)](https://f-droid.org)

A modern, privacy-focused local video player for Android built with **Kotlin** and **Jetpack Compose**. 100% open source, zero ads, zero tracking, zero proprietary dependencies.

---

## Features

### Smart File Management
- **Folder Browser**: Navigate your storage tree — folders, subfolders, all videos organized exactly as they are on disk
- **Format Support**: MP4, MKV, AVI, MOV, WMV, M4V, 3GP, FLV and more
- **Fast Indexing**: Background video scanner with StateFlow caching — no repeated scans
- **Thumbnail Generation**: Automatic video thumbnails with memory + disk cache
- **Open With**: Receive video files from other apps and play them directly, with full playlist support

### Playback Engine
- **Media3 / ExoPlayer**: Hardware-accelerated playback for all major formats
- **Gesture Controls**: Swipe left/right to seek, swipe up/down on each side to adjust brightness and volume
- **Double-Tap Seek**: Configurable skip amount (5–30 seconds)
- **Audio & Subtitle Track Selector**: Switch between multiple audio streams and subtitle tracks mid-playback
- **Auto-Rotation**: Landscape/portrait adapts automatically to video aspect ratio
- **Background Playback**: MediaSessionService keeps playing when you switch apps
- **Media Notification**: Full playback controls in the notification shade and on lock screen
- **Headphone Support**: Play/pause and track skip via hardware media buttons
- **Mini Player**: Persistent mini player at the bottom while browsing your library

### Picture-in-Picture
- **Auto PiP**: Automatically enters PiP mode when you leave the app
- **PiP Controls**: Previous, play/pause, and next buttons directly in the PiP window

### Playlists
- **Folder Playlists**: Play all videos in a folder in order
- **Shuffle Mode**: Instantly shuffle any folder into a randomized playlist
- **Playlist Navigator**: Sequential and shuffle navigation across the current playlist

### DLNA Casting
- **Cast to TV**: Stream videos to any DLNA/UPnP renderer on your local network (smart TVs, media players, etc.)
- **100% Open Source**: Implemented via SSDP discovery + UPnP AvTransport SOAP — no Google Cast SDK or proprietary libraries
- **Local HTTP Server**: NanoHTTPD-based server serves the file to the renderer directly from your device

### Private Folders
- **True Encryption**: Lock any folder with a password — files are protected using PBKDF2 key derivation + XOR header obfuscation + AES-CBC encrypted manifest
- **Zero Footprint**: Locked folders are invisible in other gallery apps and media scanners
- **Transparent Playback**: Locked videos play seamlessly via a custom Media3 DataSource that reverses encryption on-the-fly — no temporary decrypted copies on disk
- **Password-Free Browsing**: The folder registry persists in SharedPreferences so locked folders remain visible in the app without re-entering the password unless you actually open one

### Video Tools
- **Video Trimmer**: Cut any video to a precise start/end range with a frame-accurate timeline preview — exports without re-encoding (remux only, lossless)
- **Video Remuxer**: Fix broken MP4 files missing duration metadata by rewriting the container without re-encoding

### Settings
- **Playback**: Auto-hide controls, auto-PiP on exit, configurable double-tap seek duration
- **Interface**: Theme selection (Light / Dark / System), language switching (Portuguese / English)
- **Display**: Customize how videos and folders are listed
- **About**: App version and links

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin + Coroutines |
| UI | Jetpack Compose + Material 3 |
| Video | Media3 (ExoPlayer) |
| Background Playback | MediaSessionService |
| Casting | DLNA/UPnP (SSDP + SOAP), NanoHTTPD |
| Storage | MediaStore API + custom folder scanner |
| Thumbnails | Coil + MediaMetadataRetriever |
| Encryption | PBKDF2 + XOR + AES-CBC (javax.crypto) |
| Video Editing | Android MediaExtractor / MediaMuxer |
| Serialization | GSON |
| Build | Gradle with Kotlin DSL |

---

## Privacy

NekoVideo does **not** collect any data. There are no analytics, no crash reporting services, no ads, and no network requests except for DLNA device discovery on your local network (which you explicitly trigger).

All video processing (thumbnails, trimming, remuxing, encryption) happens entirely on-device.

---

## Requirements

- Android 11 (API 30) or higher
- Storage permission for local file access

---

## License

This project is licensed under the **GNU General Public License v3.0**.

See [LICENSE](LICENSE) for the full text.

**Copyright © 2025 NKL's**

---

*Built with Kotlin & Jetpack Compose*
