# TODO

## Built, to check on the TV (2026-10-31)

Player:
- Audio/Subtitles buttons read "Japanese · DTS-HD MA Stereo"; a track's own title only when it says more.
- The audio/subtitle remote buttons show the info box alone, with the stepped track underneath.
- Spinner while loading, buffering or waiting on Plex (after 0.5 s).
- OK on the progress bar pauses and resumes (still jumps while scrubbing).
- "Skip intro" / "Next episode" from Plex's markers while the controls are hidden (OK presses it). The
  server has intro markers; no credits markers seen yet.
- Resume only from Continue watching and the show page's Play; everything else starts from 0:00.
- Audio and subtitle language (or subtitles off) remembered per show.
- Direct play errors: one retry from the same spot, then Plex's conversion, then the error.
- Paused 5 minutes: the screen is let go, so the TV's screensaver can run.
- Stopped in the end credits (or past 95%): marked watched.

Screens:
- Hold-OK menu: Resume from …, Remove from Continue watching, Go to show, Info (short summary popup).
- Library grid: sort (A–Z, Recently added, Year), an A–Z jump column, two-line poster titles.
- Show page: moving up/down into a season lands on its first episode; episode cards show the air date.
- Search keeps the last search until cleared, with recent searches as quick picks.
- Settings (Home's tile, was Edit): libraries on Home, clear caches, sign out, build info.

- Settings → Wake server: the server's MAC address typed in (separators optional), with "Wake now". With
  one saved, the "couldn't reach" screens get a focused "Wake server" button. Not tried on a sleeping server.
- Info popup lists every audio track (one per line) and every subtitle language, no "+ N more".

Updates:
- Release key in `~/.simplex-release` (back it up: without it no release can update the TV). Versions are
  0.2.<commit count>. `tools/release.sh` builds, checks the signature and publishes a GitHub release.
- Release builds look for a newer GitHub release: an "Update" tile on Home, installed from Settings.
- Switching the TV to the release key needs one uninstall and reinstall (signing in again).

## Open

- **Sample 08: the AC3 compatibility track is decoded instead of passed through.** The HDMI output accepts
  AC3 and sample 01 (AC3 only) passes through; here ExoPlayer gave the AC3 track to the FFmpeg decoder. The
  file's default track is TrueHD, likely related. Debug builds log track mapping (logcat tag EventLogger):
  replay 08, pick the AC3 track, read the log.

## Later

- **Brighter SDR preview thumbnails.** The scrubbing thumbnails are drawn exactly as Plex made them (checked
  on Totoro: same levels as the film's own frames, and on screen). They still look dark and faded next to
  the video, because the Sony's picture mode brightens and adds contrast and colour to the video layer only,
  not to the app's graphics on top. Idea: a per-channel lookup like the HDR tone-map in `PreviewThumbs.kt`
  (deeper blacks, a bit more contrast and saturation), tuned by eye on the TV.

Decided against: moving image subtitles above the open controls, an "Up next" countdown, a clock, more rows
on Home, a movie info page (no detail pages is a core goal), Back twice to exit, automating the TrueHD script.
