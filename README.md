# NekoVideo

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blueviolet?logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-11%2B-green?logo=android)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-latest-blue?logo=android)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/Media3%2FExoPlayer-latest-orange?logo=android)](https://developer.android.com/guide/topics/media/media3)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

NekoVideo is an open-source local video player for Android built with Kotlin and Jetpack Compose.

The app focuses on local playback, folder-based organization, private folders, tag-based organization, and DLNA casting without depending on proprietary cast SDKs.

## Highlights

- Folder-based local video library
- Modern player built on Media3 / ExoPlayer
- Picture-in-Picture, mini player, and background playback
- DLNA / UPnP casting to TVs and renderers on the local network
- Private folders with password protection, hidden folders, and biometric unlock support
- Video tags with separate normal/private scopes
- Built-in MP4 metadata repair via remux
- No ads, no analytics, no cloud dependency

## What The App Does

### Library and File Management

- Browses your storage as real folders instead of forcing a media-library-only view
- Scans and caches folders that contain videos for faster navigation
- Supports common formats such as `mp4`, `mkv`, `webm`, `avi`, `mov`, `wmv`, `m4v`, `3gp`, and `flv`
- Generates thumbnails and caches them in memory and on disk
- Lets you search items inside the current folder
- Supports sorting by name, date, and file size
- Can show video duration and file size in the grid/list
- Allows creating folders, renaming items, moving items, deleting items, and sharing videos
- Supports opening videos from other apps via `VIEW` and `SEND_MULTIPLE` intents

### Playback

- Uses Media3 / ExoPlayer for local playback
- Folder playback with playlist navigation
- Shuffle playback for the current folder tree
- Double-tap seek with configurable skip duration
- Gesture-based seeking in the player
- Audio track and subtitle track selection
- Automatic orientation behavior based on the video
- Background playback through `MediaSessionService`
- Media notification and lock-screen controls
- Persistent mini player while browsing the app

### Picture-in-Picture

- Supports Android PiP mode
- Can enter PiP automatically when leaving the app
- Includes previous, play/pause, and next PiP actions

### DLNA Casting

- Discovers DLNA / UPnP renderers on the local network using SSDP
- Streams local files through an embedded HTTP server
- Supports playlist casting, next/previous navigation, and playback state polling
- Works without Google Cast SDK or closed-source casting dependencies

### Tags

- Create, rename, and delete tags
- Assign tags to one or many videos
- Keep separate tag scopes for normal content and private content
- Shuffle videos using tag include/exclude filters
- Preserves tag references when files or folders are renamed or moved

### Private Folders and Protected Content

- Supports a dedicated secure folder path and hidden app-managed folders
- Can lock folders behind a password
- Supports biometric unlock after password setup
- Keeps protected folders hidden by default when the app starts
- Plays locked content without creating decrypted temporary copies on disk

Important: the current protection model is not full-file encryption for every byte of the video. It uses password-derived data, obfuscated file and folder names, protected manifests, and on-the-fly header deobfuscation during playback.

### Video Utilities

- Repairs some problematic MP4 files by remuxing them without re-encoding
- Rewrites container metadata while preserving original media streams

### Settings

- Playback settings for auto-hide controls, auto PiP, and double-tap seek duration
- Interface settings for theme and app language
- Display settings for durations and file sizes
- Storage settings for clearing thumbnail cache
- Security settings for password changes and biometric unlock
- Tag management screens for normal and private tags

## Supported Languages

The app currently includes support for:

- Portuguese
- English
- Spanish
- French
- German
- Russian
- Hindi
- System default mode

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin + Coroutines |
| UI | Jetpack Compose + Material 3 |
| Playback | AndroidX Media3 / ExoPlayer |
| Background media | MediaSessionService |
| Navigation | Navigation Compose |
| Thumbnails | Coil, Glide, MediaMetadataRetriever |
| Local database | Room |
| Serialization | Gson |
| Casting | DLNA / UPnP via SSDP + SOAP |
| Local streaming | NanoHTTPD |
| Build | Gradle Kotlin DSL |

## Privacy

NekoVideo does not include analytics, ads, or third-party tracking.

Network activity is limited to local-network casting features when you use DLNA discovery or playback control.

Most media processing is done on-device, including thumbnail generation, tag storage, remux operations, and private-folder handling.

## Requirements

- Android 11 (API 30) or higher
- Access to local storage
- `All files access` may be required for the folder-based browsing model on supported Android versions

## Building

```bash
./gradlew assembleDebug
```

Project info:

- `minSdk = 30`
- `targetSdk = 36`
- Current app version in the project: `1.2.0`

## License

This project is licensed under the GNU General Public License v3.0.

See [LICENSE](LICENSE) for the full text.

Copyright © 2025 NKL's
