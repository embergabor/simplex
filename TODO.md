# TODO

## Player polish (planned 2026-10-31)

- **Track labels.** The Audio and Subtitles buttons show "Language · format" ("Japanese · DTS-HD MA 2.0",
  "English · PGS"), plus the track's own title only when it has one ("Commentary"), never the file's raw
  track title.
- **Buffering spinner.** A small spinner in the middle while loading or buffering (also while Plex restarts
  a conversion after a seek), shown only after about 0.5 s so quick waits don't flash it.
- **OK toggles play/pause** on the progress bar when not scrubbing; while scrubbing it still jumps there.
- **Intro and credits markers.** Plex's markers (`includeMarkers=1`): a "Skip intro" button during the
  intro and a "Next episode" button during the credits; OK presses it. Nothing happens automatically.
- **Resume rules.** Only Home's Continue watching row and the show page's Play button resume; everything
  else (episode cards, movies, favourites, search, folders) starts from the beginning. The hold-OK menu
  offers "Resume from 23:10" on partly watched items (on Continue watching it stays "Play from beginning").
- **Languages per show.** Changing audio or subtitle language (or subtitles off) is remembered on the TV per
  show and applied to its episodes whenever they start, when the file has that language.
- **Playback errors.** When direct play fails: retry once from the same spot, then play through Plex's
  conversion, and show the error only if that fails too.

Decided against for now: moving image subtitles above the open controls, an "Up next" countdown, a clock.

## Bugs

- **Sample 08: the AC3 compatibility track is decoded instead of passed through.** The HDMI output accepts
  AC3 and sample 01 (AC3 only) passes through; here ExoPlayer gave the AC3 track to the FFmpeg decoder. The
  file's default track is TrueHD, likely related. Debug builds now log track mapping (logcat tag
  EventLogger): replay 08, pick the AC3 track, read the log.

## Later

- **Brighter SDR preview thumbnails.** The scrubbing thumbnails are drawn exactly as Plex made them (checked
  on Totoro: same levels as the film's own frames, and on screen). They still look dark and faded next to
  the video, because the Sony's picture mode brightens and adds contrast and colour to the video layer only,
  not to the app's graphics on top. Idea: a per-channel lookup like the HDR tone-map in `PreviewThumbs.kt`
  (deeper blacks, a bit more contrast and saturation), tuned by eye on the TV.
