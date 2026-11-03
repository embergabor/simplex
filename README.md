# Simplex

A deliberately simple Android TV client for an existing Plex Media Server. It shows your movies, TV shows
and "other videos" libraries and plays the files as they are. It has no recommendations, descriptions,
cast or reviews.

Built for a Sony Bravia (Google TV) sending audio over eARC to an AV receiver, but it should run on any
Android TV 8.0+.

## What it does

- **Home:** Continue watching (the one place OK resumes) and your video libraries as tiles, under a
  menu with Search, Home, PlexVerse, Kids and Settings. The focused title's artwork fills the background,
  blurred. Music and photo libraries are left out.
- **Libraries:** a grid of 2:3 posters, A–Z (with a letter rail on the right to jump), by date added or by
  year. Show posters count their unwatched episodes. "Other videos" libraries are browsed by folder.
- **No detail pages.** OK plays from the beginning. Hold OK for a menu: resume, mark watched or unwatched,
  favourites, and Info, a short summary of the file.
- **Shows:** Play (continue where you left off), Shuffle and Shuffle favorites, then your favourite
  episodes (Plex label `fav`) and one row per season. Audio and subtitle languages are remembered per show.
- **Search** across the libraries on Home, including episode descriptions, typed or spoken into the
  remote's mic.
- **PlexVerse:** every Plex server your account can reach, merged into one Movies, Series and Videos
  view, with a Continue watching row across them. A title on several servers shows once, plays from the
  first server in your order that's reachable, and its progress goes to every copy. Customize sorts each
  server's libraries into the categories.
- **Kids:** when PlexVerse keeps kids' libraries separate, their movies and series in one grid of big
  posters. Surprise me! spins a reel of their posters, stops on a random one and plays it after a short
  countdown (a series plays a random episode).
- **Player** (Media3 ExoPlayer):
  - Direct play. Video only goes through the TV's hardware decoder, which also does the deinterlacing.
    Video the TV can't decode (VC-1 on many TVs) is converted by Plex instead of playing as a black picture.
  - Audio passes through as a bitstream when the TV and receiver accept it (AC3, E-AC3 with Atmos, DTS,
    DTS-HD; some TVs send only the DTS core of DTS-HD). Multichannel audio the TV would only send as stereo
    (TrueHD, LPCM, FLAC, AAC) is converted by Plex to 5.1. When the file also has a same-language
    compatibility track, Simplex asks which to use.
  - Left or right jumps 10 s either way; held, they drag the playhead with Plex's preview thumbnails
    (tone-mapped for HDR) while the video keeps playing.
  - Down from the controls opens the file's chapters, with their thumbnails.
  - Previous and next episode, and Skip intro from Plex's markers. The remote's audio and subtitle buttons
    step through the tracks.
  - Optional readout of the video and audio formats, how the audio reaches the receiver and whether Plex
    is converting anything (Settings, or the remote's Info button).
  - Blu-ray (PGS) and DVD (VobSub) subtitles are brightened to true white. Text subtitles get a black
    outline.
  - Back from a video puts focus on the card it was started from.
- **Settings:** light, dark, or dark between sunset and sunrise where the server is; the playback
  readout; blurred pictures of episodes you haven't started (no spoilers); which libraries show on Home;
  the server, and waking it with Wake-on-LAN when it's asleep (its MAC address is typed in once); clearing the cached thumbnails and artwork;
  installing a newer version from GitHub; signing out.
- **Made for slow TVs:** a short opening animation, screen changes that fade out and in, and focus that
  lifts the card with a shadow, all drawn to keep 60 fps on a TV's modest GPU and CPU.

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
