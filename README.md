# Simplex

A deliberately simple Android TV client for an existing Plex Media Server. It shows your movies, TV shows
and "other videos" libraries and plays the files as they are. It has no recommendations, descriptions,
cast or reviews.

Built for a Sony Bravia (Google TV) sending audio over eARC to an AV receiver, but it should run on any
Android TV 8.0+.

## What it does

- **Home:** Continue watching, plus your video libraries as tiles. You can hide the ones you don't use.
  Music and photo libraries are left out.
- **Libraries:** a plain A–Z grid of 2:3 posters. "Other videos" libraries are browsed by folder.
- **Movies:** no detail page. OK resumes, or starts over if you'd finished it. Hold OK for a menu (play
  from beginning, mark watched or unwatched).
- **Shows:** Play (continue where you left off) and Shuffle, then one row per season.
- **Player** (Media3 ExoPlayer):
  - Direct play. Video only goes through the TV's hardware decoder, which also does the deinterlacing.
  - Audio passes through as a bitstream when the TV and receiver accept it (AC3, E-AC3, DTS, DTS-HD).
    Multichannel audio the TV would only send as stereo (TrueHD, LPCM, FLAC, AAC) uses a same-language
    compatibility track when the file has one, otherwise Plex converts it to 5.1.
  - Seek by holding left or right to drag the playhead, with Plex's preview thumbnails (tone-mapped
    for HDR). The video keeps playing while you look.
  - Previous and next episode, and an info box with the video and audio formats and whether Plex is
    converting anything.
  - Blu-ray (PGS) and DVD (VobSub) subtitles are brightened to true white. Text subtitles get a black
    outline.

## Build and install

You need JDK 17+ and the Android SDK.

```bash
./gradlew assembleRelease
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key; it's meant for your own TV. Sign in on the TV with the
code shown at plex.tv/link.

## Server script: compatibility tracks for TrueHD

[`tools/add-compat-tracks.sh`](tools/add-compat-tracks.sh) runs on the Plex server. It adds an AC3 5.1
640 kbps track next to TrueHD tracks that have no same-language AC3, E-AC3 or DTS track, so those files
can play with surround sound passed through, without Plex converting anything. Everything else in the
file is copied untouched. Start with `--dry-run`, and see `--help` for the rest.

## License

[MIT](LICENSE)
