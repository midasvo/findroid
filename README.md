![Findroid banner](images/findroid-banner.png)

# Findroid CE (Community Edition)

A maintained fork of [Findroid](https://github.com/jarnedemeulemeester/findroid), the native Jellyfin Android client.

## Why this fork?

The original Findroid is an excellent Jellyfin client, but many community PRs with useful features aren't being merged. Findroid CE merges select community contributions and keeps dependencies up to date.

## Installing

### Via Obtainium (recommended)

Add this repo URL in [Obtainium](https://github.com/ImranR98/Obtainium): `https://github.com/midasvo/findroid-ce`

Obtainium will automatically check for new releases and notify you of updates.

### Manual

Download the latest APK from the [Releases](https://github.com/midasvo/findroid-ce/releases) page.

Findroid CE uses a different application ID (`nl.midasvo.findroid.ce`) so it can be installed alongside the original Findroid.

## Screenshots

| Home | Library | Movie | Season | Episode |
|------|---------|-------|--------|---------|
| ![Home](fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png) | ![Library](fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png) | ![Movie](fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png) | ![Season](fastlane/metadata/android/en-US/images/phoneScreenshots/4_en-US.png) | ![Episode](fastlane/metadata/android/en-US/images/phoneScreenshots/5_en-US.png) |

## Features

- Completely native interface
- Supported media: movies, series, seasons, episodes (direct play, no transcoding)
- Offline playback / downloads (including SD card support)
- ExoPlayer
  - Video: H.263, H.264, H.265, VP8, VP9, AV1
  - Audio: Vorbis, Opus, FLAC, ALAC, PCM, MP3, AAC, AC-3, E-AC-3, DTS, DTS-HD, TrueHD
  - Subtitles: SRT, VTT, SSA/ASS, PGSSUB
- mpv
  - Containers: mkv, mov, mp4, avi
  - Video: H.264, H.265, H.266, VP8, VP9, AV1
  - Audio: Opus, FLAC, MP3, AAC, AC-3, E-AC-3, TrueHD, DTS, DTS-HD
  - Subtitles: SRT, VTT, SSA/ASS, DVDSUB
- Picture-in-picture mode
- Media chapters (timeline markers, chapter navigation)
- Trickplay (requires Jellyfin 10.9+)
- Media segments with skip button and auto-skip (requires Jellyfin 10.10+)

## What's different from upstream?

Findroid CE adds the following on top of upstream Findroid:

- **Per-episode download progress** — see real-time download status (pending, downloading, completed, failed) directly in the season episode list, no need to open each episode
- **Interactive download buttons per episode** — tap to download or delete individual episodes inline, Netflix/Disney+ style
- **Bulk season & series download** — download an entire season or series with one tap, with queued concurrent downloading
- **Configurable max concurrent downloads** — limit how many episodes download simultaneously (default: 2), configurable in settings
- **Download feedback** — toast messages summarizing bulk download results (started, skipped, failed)
- **Season download status on show screen** — see "3/10 downloaded" per season in the season selection dialog
- **Redesigned downloads screen** — active downloads with progress at the top, completed items below, storage usage info at the bottom
- **Dependency updates** — kept up to date via Renovate

## Upstream sync

This fork is periodically synced with upstream.

## License

GPLv3 — same as upstream. See [LICENSE](LICENSE).

The logo is a combination of the Jellyfin logo and the Android robot. The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License.

## Credits

All credit to [Jarne Demeulemeester](https://github.com/jarnedemeulemeester) and the [Findroid contributors](https://github.com/jarnedemeulemeester/findroid/graphs/contributors).
