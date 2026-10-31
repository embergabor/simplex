# Simplex

A deliberately simple Android TV client for an existing Plex Media Server. It shows your movies, TV shows
and "other videos" libraries and plays the files as they are. It has no recommendations, descriptions,
cast or reviews.

Built for a Sony Bravia (Google TV) sending audio over eARC to an AV receiver, but it should run on any
Android TV 8.0+.

## What it does

- **Home:** Continue watching (the one place OK resumes), Search, and your video libraries as tiles.
  Settings hides the libraries you don't use. Music and photo libraries are left out.
- **Libraries:** a grid of 2:3 posters, A–Z (with a letter column to jump), by date added or by year.
  "Other videos" libraries are browsed by folder, with your favourites on top.
- **No detail pages.** OK plays from the beginning. Hold OK for a menu: resume, mark watched or unwatched,
  favourites, and Info, a short summary of the file.
- **Shows:** Play (continue where you left off), Shuffle and Shuffle favorites, then your favourite
  episodes (Plex label `fav`) and one row per season. Audio and subtitle languages are remembered per show.
- **Search** across the libraries on Home, including episode descriptions.
- **Player** (Media3 ExoPlayer):
  - Direct play. Video only goes through the TV's hardware decoder, which also does the deinterlacing.
  - Audio passes through as a bitstream when the TV and receiver accept it (AC3, E-AC3, DTS, DTS-HD).
    Multichannel audio the TV would only send as stereo (TrueHD, LPCM, FLAC, AAC) is converted by Plex to
    5.1. When the file also has a same-language compatibility track, Simplex asks which to use.
  - Seek by holding left or right to drag the playhead, with Plex's preview thumbnails (tone-mapped
    for HDR). The video keeps playing while you look.
  - Previous and next episode, Skip intro from Plex's markers, and an info box with the video and audio
    formats and whether Plex is converting anything. The remote's audio and subtitle buttons step
    through the tracks.
  - Blu-ray (PGS) and DVD (VobSub) subtitles are brightened to true white. Text subtitles get a black
    outline.

## Build and install

You need JDK 17+ and the Android SDK.

```bash
./gradlew assembleRelease
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

Sign in on the TV with the code shown at plex.tv/link. Release builds are signed with a key kept outside
the repo (`~/.simplex-release`, see `app/build.gradle.kts`); without it they fall back to the debug key.
`tools/release.sh` publishes a signed build as a GitHub release, which release builds offer to install.

## Server script: compatibility tracks for TrueHD

[`tools/add-compat-tracks.sh`](tools/add-compat-tracks.sh) runs on the Plex server. It adds an AC3 5.1
640 kbps track next to TrueHD tracks that have no same-language AC3, E-AC3 or DTS track, so those files
can play with surround sound passed through, without Plex converting anything. Everything else in the
file is copied untouched. Start with `--dry-run`, and see `--help` for the rest.

## License

[MIT](LICENSE)
