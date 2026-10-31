# TODO

- **Brighter SDR preview thumbnails.** The scrubbing thumbnails are drawn exactly as Plex made them (checked
  on Totoro: same levels as the film's own frames, and on screen). They still look dark and faded next to
  the video, because the Sony's picture mode brightens and adds contrast and colour to the video layer only,
  not to the app's graphics on top. Idea: a per-channel lookup like the HDR tone-map in `PreviewThumbs.kt`
  (deeper blacks, a bit more contrast and saturation), tuned by eye on the TV.
