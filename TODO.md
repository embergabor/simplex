# TODO

## Library script: give TrueHD files a compatibility track

The A80J passes AC3, E-AC3 (incl. Atmos/JOC), DTS and DTS-HD to the receiver over eARC, but not TrueHD.
Today, a TrueHD file without a same-language compatibility track makes Plex convert the audio, and then
subtitles are either dropped or burned in (the player asks).

Write a bash script that runs over the Plex library and fixes those files once:

1. Find affected files: a TrueHD audio track with no E-AC3/AC3/DTS track in the same language
   (`ffprobe` or `mkvmerge -J`).
2. Demux the TrueHD track(s).
3. Re-encode each to **AC3 5.1 at 640 kbps** with ffmpeg (`-c:a ac3 -b:a 640k -ac 6`), same language
   and a title like "AC3 5.1 (compat)". Decided because:
   - DD+ Atmos (E-AC3 JOC) needs Dolby's commercial encoder; ffmpeg can't make it.
   - PCM/FLAC won't help: the A80J only offers 2-channel PCM to apps on eARC.
   - ffmpeg's E-AC3 encoder is also limited to 5.1 and less mature; its DTS encoder is experimental.
   - AC3 640k is ffmpeg's most mature encoder, near transparent, and always passes through.
4. Mux the compatibility track back in **next to** the original TrueHD (keep TrueHD for lossless/Atmos on
   other players, and keep all subtitles, chapters and attachments untouched), then replace the file and
   let Plex rescan. The player already prefers a same-language AC3 track over converting TrueHD.
