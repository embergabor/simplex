#!/usr/bin/env bash
#
# add-compat-tracks.sh: gives TrueHD MKV files an AC3 5.1 compatibility track.
#
# The Sony A80J passes AC3, E-AC3 and DTS to the receiver, but not TrueHD. Simplex plays a same-language
# AC3/E-AC3/DTS track instead when the file has one; this script adds that track to files that don't.
#
# For every MKV under the given folders (or given directly):
#   - finds TrueHD tracks (more than 2 channels) with no AC3, E-AC3 or DTS track in the same language,
#   - encodes one AC3 5.1 640 kbps track per such language from the TrueHD (7.1 is folded down to 5.1),
#   - writes it into the file right after its TrueHD track, marked not default, titled "AC3 5.1 (compat)".
# Everything else (video, the TrueHD itself, other audio, subtitles, chapters, attachments, default and
# forced flags) is copied untouched. The new file is checked before it replaces the original.
#
# Why AC3 640k: ffmpeg can't make E-AC3 Atmos (needs Dolby's encoder), its E-AC3 encoder is also 5.1
# only and less mature, its DTS encoder is experimental, and multichannel PCM/FLAC would only reach the
# receiver as stereo. AC3 640k is ffmpeg's most mature encoder, near transparent and always passes through.
#
# Needs: bash, ffmpeg and ffprobe (4.4 or newer), jq. Run it where the files are (on the server).
#
# Usage:
#   add-compat-tracks.sh [options] <folder or file>...
#
# Options:
#   -n, --dry-run        only list what would change
#   -k, --keep-original  keep each original next to the new file as "<name>.mkv.orig"
#   -h, --help           this text
#
# Optional, to make Plex rescan once at the end (otherwise its own scanning picks the changes up):
#   PLEX_URL=http://127.0.0.1:32400 PLEX_TOKEN=... add-compat-tracks.sh /media/movies
#
# Safe to run again: files that already have a compatibility track are skipped.

set -euo pipefail

DRY_RUN=0
KEEP_ORIGINAL=0
TITLE="AC3 5.1 (compat)"

usage() { sed -n '3,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }

paths=()
while [ $# -gt 0 ]; do
    case "$1" in
        -n|--dry-run) DRY_RUN=1 ;;
        -k|--keep-original) KEEP_ORIGINAL=1 ;;
        -h|--help) usage; exit 0 ;;
        --) shift; paths+=("$@"); break ;;
        -*) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
        *) paths+=("$1") ;;
    esac
    shift
done
[ ${#paths[@]} -gt 0 ] || { usage >&2; exit 2; }

for tool in ffmpeg ffprobe jq; do
    command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }
done

log() { printf '%s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }

# The temporary output of the file being processed; removed if the script is interrupted.
tmp=""
cleanup() { if [ -n "$tmp" ]; then rm -f "$tmp"; fi; }
trap cleanup EXIT
trap 'exit 130' INT TERM

# Prints "<stream index> <channels> <language>" for each TrueHD track that needs a compatibility track:
# per language, the TrueHD with the most channels, if that language has no AC3/E-AC3/DTS track.
# A track without a language counts as "und".
needs_compat() {
    jq -r '
        def lang: ((.tags.language // "und") | ascii_downcase);
        [.streams[] | select(.codec_type == "audio")] as $audio
        | [$audio[] | select(.codec_name == "ac3" or .codec_name == "eac3" or .codec_name == "dts") | lang] as $covered
        | [$audio[] | select(.codec_name == "truehd" and (.channels // 0) > 2)]
        | group_by(lang)
        | map(max_by(.channels))[]
        | select((lang) as $l | $covered | index($l) | not)
        | "\(.index) \(.channels) \(lang)"
    '
}

file_size_kb() { du -k "$1" | cut -f1; }
free_kb() { df -Pk "$1" | awk 'NR == 2 { print $4 }'; }

# Copies permissions and, when allowed, ownership from $1 to $2 (GNU and BSD stat).
copy_attributes() {
    local mode owner
    mode=$(stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1")
    owner=$(stat -c %u:%g "$1" 2>/dev/null || stat -f %u:%g "$1")
    chmod "$mode" "$2"
    chown "$owner" "$2" 2>/dev/null || true
}

changed=0
skipped=0
failed=0

process() {
    local file=$1 dir base json targets total
    dir=$(dirname "$file")
    base=$(basename "$file")

    if ! json=$(ffprobe -v error -show_entries 'stream=index,codec_type,codec_name,channels:stream_tags=language:format=duration' \
            -of json "$file" </dev/null); then
        warn "Can't read $file"
        failed=$((failed + 1))
        return
    fi
    targets=$(needs_compat <<<"$json")
    if [ -z "$targets" ]; then
        skipped=$((skipped + 1))
        return
    fi

    local summary
    summary=$(awk '{ printf "%s%s %sch", (NR > 1 ? ", " : ""), $3, $2 }' <<<"$targets")
    if [ "$DRY_RUN" = 1 ]; then
        log "Would add AC3 for TrueHD ($summary): $file"
        changed=$((changed + 1))
        return
    fi

    local need have
    need=$(( $(file_size_kb "$file") * 105 / 100 ))
    have=$(free_kb "$dir")
    if [ "$have" -lt "$need" ]; then
        warn "Not enough free space next to $file (needs ${need} kB, has ${have} kB)"
        failed=$((failed + 1))
        return
    fi

    log "Adding AC3 for TrueHD ($summary): $file"

    # Output streams in input order, with the new AC3 right after the TrueHD it's made from.
    total=$(jq '.streams | length' <<<"$json")
    local maps=() encodes=() out=0 i index channels lang
    for ((i = 0; i < total; i++)); do
        maps+=(-map "0:$i")
        out=$((out + 1))
        while read -r index channels lang; do
            [ "$index" = "$i" ] || continue
            maps+=(-map "0:$i")
            encodes+=(
                -c:$out ac3 -b:$out 640k -ac:$out "$(( channels < 6 ? channels : 6 ))"
                -metadata:s:$out "title=$TITLE" -metadata:s:$out "language=$lang"
                # Statistics copied from the TrueHD would be wrong for the new track.
                -metadata:s:$out BPS= -metadata:s:$out NUMBER_OF_BYTES= -metadata:s:$out NUMBER_OF_FRAMES=
                -metadata:s:$out DURATION=
                -disposition:$out 0
            )
            out=$((out + 1))
        done <<<"$targets"
    done

    # Hidden name in the same folder, so Plex ignores it and the final rename is atomic.
    tmp="$dir/.${base%.*}.compat-tmp.mkv"
    if ! ffmpeg -hide_banner -nostdin -loglevel error -stats -y -i "$file" \
            "${maps[@]}" -map_metadata 0 -map_chapters 0 \
            -c copy "${encodes[@]}" \
            -default_mode passthrough -max_muxing_queue_size 9999 \
            "$tmp"; then
        warn "ffmpeg failed on $file; left unchanged"
        rm -f "$tmp"; tmp=""
        failed=$((failed + 1))
        return
    fi

    # Check the result: every stream there plus the new ones, and the same length.
    local result expected got d_in d_out
    result=$(ffprobe -v error -show_entries 'stream=index:format=duration' -of json "$tmp" </dev/null) || result='{}'
    expected=$((total + $(wc -l <<<"$targets")))
    got=$(jq '.streams | length' <<<"$result")
    d_in=$(jq -r '.format.duration // "0"' <<<"$json")
    d_out=$(jq -r '.format.duration // "0"' <<<"$result")
    if [ "$got" != "$expected" ] || ! awk -v a="$d_in" -v b="$d_out" 'BEGIN { d = a - b; exit !(d < 2 && d > -2 && b > 0) }'; then
        warn "Check failed for $file (streams $got/$expected, duration $d_out/$d_in s); left unchanged"
        rm -f "$tmp"; tmp=""
        failed=$((failed + 1))
        return
    fi

    copy_attributes "$file" "$tmp"
    if [ "$KEEP_ORIGINAL" = 1 ]; then
        mv "$file" "$file.orig"
    fi
    mv -f "$tmp" "$file"
    tmp=""
    changed=$((changed + 1))
}

while IFS= read -r -d '' file; do
    process "$file"
done < <(find "${paths[@]}" -type f -iname '*.mkv' ! -name '.*' -print0)

if [ "$DRY_RUN" = 1 ]; then
    log "Dry run: $changed file(s) would get a compatibility track, $skipped need none, $failed unreadable."
    exit 0
fi
log "Done: $changed file(s) changed, $skipped needed nothing, $failed failed."

if [ "$changed" -gt 0 ] && [ -n "${PLEX_URL:-}" ] && [ -n "${PLEX_TOKEN:-}" ]; then
    if curl -fsS -o /dev/null "${PLEX_URL%/}/library/sections/all/refresh?X-Plex-Token=$PLEX_TOKEN"; then
        log "Asked Plex to rescan its libraries."
    else
        warn "Couldn't reach Plex at $PLEX_URL to start a rescan."
    fi
fi

[ "$failed" -eq 0 ]
