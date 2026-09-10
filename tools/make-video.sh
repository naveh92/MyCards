#!/usr/bin/env bash
#
# Composites the promo video: VideoStage.java's stills, the device recordings, and a
# crossfade between each scene.
#
# Each scene is built as background -> recording -> bezel, in that order:
#
#     stage-NN.png     the stage, the copy, and the shadow the phone casts
#     <clip>.mp4       the screen recording, scaled into SCREEN_X/Y/W/H
#     bezel.png        the ring that rounds off the recording's square corners
#
# A scene with no clip (the opening card and the endcard) is just the still, held.
#
# ⚠️ NO AUDIO, ON PURPOSE. Play autoplays the listing video muted and most people never
# unmute it, so every claim is on screen as type. A silent track is also the only one that
# cannot arrive with a licensing problem attached.
#
# Usage: tools/make-video.sh <stage-dir> <clips-dir> <out.mp4>
set -euo pipefail

STAGE="${1:?usage: make-video.sh <stage-dir> <clips-dir> <out.mp4>}"
CLIPDIR="${2:?}"
OUT="${3:?}"

# SCREEN_*, W, H, CLIPS[], SECS[] -- written by VideoStage.java so the two cannot drift.
# NOTE: stage.env defines CLIPS=() as an array, so the clips DIRECTORY is CLIPDIR -- naming
# it CLIPS silently replaced the array and every scene fell back to a held still.
# shellcheck disable=SC1090
source "$STAGE/stage.env"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FADE=0.45           # crossfade between scenes
FPS=30

n=${#CLIPS[@]}
echo "== building $n scenes"

for i in $(seq 0 $((n - 1))); do
    stage_png=$(printf "%s/stage-%02d.png" "$STAGE" $((i + 1)))
    clip="${CLIPS[$i]}"
    secs="${SECS[$i]}"
    seg="$TMP/seg-$i.mp4"

    if [ "$clip" = "-" ] || [ ! -f "$CLIPDIR/$clip.mp4" ]; then
        [ "$clip" = "-" ] || echo "   (no recording for $clip -- holding the still)"
        ffmpeg -y -loglevel error -loop 1 -t "$secs" -i "$stage_png" \
            -vf "fps=$FPS,format=yuv420p" -c:v libx264 -preset slow -crf 18 "$seg"
    else
        # -stream_loop keeps a recording that ran short filling the scene rather than
        # freezing on its last frame, which reads as the app having hung.
        ffmpeg -y -loglevel error \
            -loop 1 -t "$secs" -i "$stage_png" \
            -stream_loop -1 -t "$secs" -i "$CLIPDIR/$clip.mp4" \
            -i "$STAGE/bezel.png" \
            -filter_complex "\
                [1:v]scale=${SCREEN_W}:${SCREEN_H}:flags=lanczos,setsar=1[phone]; \
                [0:v][phone]overlay=${SCREEN_X}:${SCREEN_Y}:shortest=0[withphone]; \
                [withphone][2:v]overlay=0:0,fps=$FPS,format=yuv420p[out]" \
            -map "[out]" -t "$secs" -c:v libx264 -preset slow -crf 18 "$seg"
    fi
    printf "   scene %d/%d  %-14s %ss\n" $((i + 1)) "$n" "$clip" "$secs"
done

# Crossfade pairwise into a running file. One long filter_complex would work too, but it
# fails as a single unreadable graph; this way a bad scene names itself.
echo "== crossfading"
cur="$TMP/seg-0.mp4"
acc=0
for i in $(seq 1 $((n - 1))); do
    prev_len=$(awk -v a="$acc" -v s="${SECS[$((i - 1))]}" -v f="$FADE" 'BEGIN{print a+s-f}')
    next="$TMP/acc-$i.mp4"
    ffmpeg -y -loglevel error -i "$cur" -i "$TMP/seg-$i.mp4" \
        -filter_complex "[0:v][1:v]xfade=transition=fade:duration=$FADE:offset=$prev_len,format=yuv420p" \
        -c:v libx264 -preset slow -crf 18 "$next"
    cur="$next"
    acc=$(awk -v a="$acc" -v s="${SECS[$((i - 1))]}" -v f="$FADE" 'BEGIN{print a+s-f}')
done

mkdir -p "$(dirname "$OUT")"
# yuv420p + faststart is what YouTube wants; anything else it re-encodes twice.
ffmpeg -y -loglevel error -i "$cur" -c:v libx264 -preset slow -crf 18 \
    -pix_fmt yuv420p -movflags +faststart "$OUT"

dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT")
size=$(du -k "$OUT" | cut -f1)
printf "\n%s  %.1fs  %s KB  %s\n" "$OUT" "$dur" "$size" \
    "$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$OUT")"

# Play accepts 30-120s and autoplays only the first 30.
awk -v d="$dur" 'BEGIN{
    if (d < 30) print "\n⚠️  " d "s -- Play requires at least 30s.";
    else if (d > 120) print "\n⚠️  " d "s -- Play accepts at most 120s.";
}'
