#!/usr/bin/env bash
#
# Records the screen clips that play inside the phone in the promo video.
#
# Same headless emulator and the same uiautomator navigation as tools/capture-shots.sh --
# read that file first; the device plumbing is explained there and duplicated here only so
# each script runs on its own.
#
# ⚠️ THE KEYBOARD STAYS UP IN THE VIDEO, ON PURPOSE. In a still it covers two of the three
# results and has to go; in a clip, watching the letters go in is the whole point of the
# scene. So this script does NOT call hide_kb before typing scenes -- the opposite of the
# screenshot pass.
#
# ⚠️ screenrecord STOPS ON ITS OWN AT --time-limit AND WRITES NOTHING IF KILLED WITH -9.
# It needs SIGINT to flush the MP4 moov atom; a -9 leaves a file that exists, has a size, and
# will not play. Always stop it with `pkill -INT`, then wait for the file to settle.
#
# Usage: tools/record-clips.sh <out-dir>
set -euo pipefail

OUTDIR="${1:?usage: record-clips.sh <out-dir>}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG="${PKG:-io.github.naveh92.mycards}"
export MSYS_NO_PATHCONV=1
mkdir -p "$OUTDIR"

# See tools/capture-shots.sh: the label has to exist in whatever fixture is loaded, so
# candidates are tried in order rather than one name being hardcoded.
DETAIL_CARDS=${DETAIL_CARDS:-"Holiday gift 2026|Dinner voucher|Rosh Hashana 2025"}

# ⚠️ Two dumps inside ~2s fail with "UiAutomationService ... already registered!", which
# logcat reports as a FATAL EXCEPTION that looks like the app crashing. It is not. Spacing is
# enforced here rather than at each call site.
_LAST_DUMP=0
ui_xml() {
    local now gap
    now=$(date +%s%3N)
    gap=$(( 2100 - (now - _LAST_DUMP) ))
    [ "$gap" -gt 0 ] && sleep "$(awk -v g="$gap" 'BEGIN{print g/1000}')"
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    _LAST_DUMP=$(date +%s%3N)
    "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r'
}

open_detail_card() {
    local label
    while IFS= read -r label; do
        [ -n "$label" ] || continue
        if tap_scroll "text=\"$label\"" 6 2.5 2>/dev/null; then return 0; fi
        launch
    done < <(printf '%s\n' "$DETAIL_CARDS" | tr '|' '\n')
    echo "!! none of these cards are in the fixture: $DETAIL_CARDS" >&2
    return 1
}
node_center() {
    ui_xml | tr '>' '\n' | grep -m1 -- "$1" \
        | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' \
        | awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'
}
tap() {
    local c; c="$(node_center "$1" || true)"
    if [ -z "$c" ]; then echo "!! tap target not found: $1" >&2; return 1; fi
    # shellcheck disable=SC2086
    "$ADB" shell input tap $c; sleep "${2:-1.2}"
}
tap_scroll() {
    local what="$1" i
    for i in $(seq 1 "${2:-6}"); do
        [ -n "$(node_center "$what" || true)" ] && { tap "$what" "${3:-1.5}"; return 0; }
        "$ADB" shell input swipe 540 1700 540 500 200; sleep 0.5
    done
    echo "!! not found after scrolling: $what" >&2; return 1
}
launch() {
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell am start -n "$PKG/com.mycards.ui.search.SearchActivity" >/dev/null 2>&1
    # A cold start on a software-rendered emulator needs a moment before it draws; tapping
    # into a half-drawn activity is how the pass gets ahead of the app.
    sleep 5
}

# Type one character at a time so the results visibly narrow as the query grows -- the whole
# point of the search scenes. `input text "castro"` arrives as a single edit and the list
# simply blinks from all cards to three.
type_slow() {
    local s="$1" i ch
    for i in $(seq 1 ${#s}); do
        ch="${s:i-1:1}"
        "$ADB" shell input text "$ch"
        sleep "${2:-0.45}"
    done
}

start_rec() {
    "$ADB" shell rm -f /sdcard/clip.mp4 2>/dev/null || true
    "$ADB" shell screenrecord --size 1080x2400 --bit-rate 8M --time-limit 40 /sdcard/clip.mp4 &
    REC_PID=$!
    sleep 2.5      # screenrecord takes a moment to actually start capturing frames
}

stop_rec() {
    local name="$1"
    sleep 0.8
    "$ADB" shell pkill -INT screenrecord 2>/dev/null || true
    sleep 2.5      # let it finalise the container before pulling
    wait "$REC_PID" 2>/dev/null || true
    "$ADB" pull /sdcard/clip.mp4 "$OUTDIR/$name.mp4" >/dev/null 2>&1
    "$ADB" shell rm -f /sdcard/clip.mp4 2>/dev/null || true
    local sz
    sz=$(wc -c < "$OUTDIR/$name.mp4" 2>/dev/null || echo 0)
    if [ "$sz" -lt 20000 ]; then
        echo "!! $name.mp4 is only ${sz} bytes -- screenrecord did not flush" >&2
        return 1
    fi
    printf "  %-16s %s KB  %ss\n" "$name.mp4" "$((sz / 1024))" \
        "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUTDIR/$name.mp4" 2>/dev/null)"
}

# --- the clips --------------------------------------------------------------------------------

clip_search_store() {
    launch
    tap "resource-id=\"$PKG:id/searchInput\"" 1
    start_rec
    sleep 0.6
    type_slow "castro"
    sleep 4.5          # hold on the results: this scene is 8s and the list is the point
    stop_rec "search-store"
}

clip_hebrew() {
    launch
    tap "resource-id=\"$PKG:id/searchInput\"" 1
    start_rec
    sleep 0.6
    type_slow "tshsx" 0.50
    sleep 4.0
    stop_rec "hebrew"
}

clip_store_list() {
    launch
    open_detail_card
    tap_scroll 'text="Accepted at' 4 2.5
    start_rec
    sleep 0.5
    for _ in $(seq 1 5); do "$ADB" shell input swipe 540 1750 540 650 420; sleep 0.45; done
    sleep 0.8
    stop_rec "store-list"
}

clip_card_types() {
    launch
    tap 'content-desc="Add a card"' 2
    # End icon only: touching the field would raise the keyboard over the list.
    tap "resource-id=\"$PKG:id/text_input_end_icon\"" 1.5
    # ⚠️ SCROLL BEFORE RECORDING, NOT ONLY DURING. The list opens on ten consecutive BuyMe
    # variants, so a scene captioned "32 card types" opened on what looked like one issuer and
    # only reached the other vendors near its end -- and a viewer who leaves early sees only
    # the BuyMes. Pre-scrolling starts the scene on the join, and the scroll during it carries
    # on through Max, Tav HaZahav and the rest.
    for _ in 1 2 3; do "$ADB" shell input swipe 540 1500 540 900 400; sleep 0.5; done
    sleep 0.8
    start_rec
    sleep 0.6
    for _ in $(seq 1 3); do "$ADB" shell input swipe 540 1600 540 950 450; sleep 0.6; done
    # Hold long enough for the fling to stop: a moving list can draw a row as blank space,
    # which reads as a card type with no name. See open_card_types in capture-shots.sh.
    sleep 2.5
    stop_rec "card-types"
}

# The daily balance check, shown by its outcome. The recording starts on the card so the
# viewer sees the flagged card first and then the comparison it leads to -- which is the story
# the scene's line tells, in the order it tells it.
#
# ⚠️ OPEN THE SCREEN BEFORE THE RECORDER STARTS where the scene is about what is ON a screen
# rather than about getting to it. An earlier cut recorded the tap that opened the card detail
# and spent its first four seconds on the wallet, so the frame a viewer actually saw under
# "spend it before it expires" was a list, not the balance the line was about.
clip_balance_check() {
    launch
    tap_scroll 'text="Dinner voucher"' 6 2.5
    start_rec
    sleep 1.4
    tap 'text="Add this purchase"' 2.5
    sleep 2.2
    stop_rec "balance-check"
}

clip_history() {
    launch
    tap 'content-desc="Spending history"' 2.5
    start_rec
    sleep 0.7
    for _ in $(seq 1 3); do "$ADB" shell input swipe 540 1700 540 900 500; sleep 0.6; done
    sleep 0.8
    stop_rec "history"
}

clip_refresh() {
    launch
    tap 'content-desc="More options"' 1.2
    tap 'text="Settings"' 2
    "$ADB" shell input swipe 540 1700 540 500 250; sleep 1
    start_rec
    sleep 0.6
    tap "resource-id=\"$PKG:id/syncNow\"" 2.5
    sleep 1.6
    stop_rec "refresh"
}

# ⚠️ GBOARD REMEMBERS FLOATING MODE, AND IT RUINS EVERY TYPING CLIP.
#
# Once the keyboard has been put into floating mode -- which a stray ESCAPE or a long-press
# during earlier automation can do -- it stops docking and leaves a vertical pill of
# mic/backspace/search/emoji icons hovering over the middle of the app. It survives
# `am force-stop` and `ime reset` because it is a saved Gboard preference, so the only thing
# that clears it is clearing Gboard's own data. Cheap, and it is a keyboard on an emulator.
reset_keyboard() {
    "$ADB" shell pm clear com.google.android.inputmethod.latin >/dev/null 2>&1 || true
    "$ADB" shell ime reset >/dev/null 2>&1 || true
    sleep 2
}

# ⚠️ RECORD THIS LAST, AND PUT THE APP BACK AFTERWARDS. Every other clip finds its way around
# by matching English strings, so once the app is in Hebrew none of them work. The wallet is
# the subject rather than a search: a query narrows the list to one card, and this scene has
# to earn its place on how much Hebrew is on screen.
clip_dark() {
    "$ADB" shell cmd uimode night yes >/dev/null 2>&1
    "$ADB" shell cmd locale set-app-locales "$PKG" --locales he-IL >/dev/null 2>&1
    sleep 2
    launch
    start_rec
    sleep 1.2
    "$ADB" shell input swipe 540 1600 540 1100 500; sleep 1.6
    "$ADB" shell input swipe 540 1100 540 1600 500; sleep 1.4
    stop_rec "dark"
    "$ADB" shell cmd locale set-app-locales "$PKG" --locales en-US >/dev/null 2>&1
    "$ADB" shell cmd uimode night no >/dev/null 2>&1
    sleep 2
}

main() {
    echo "== recording into $OUTDIR"
    reset_keyboard
    clip_search_store
    clip_hebrew
    clip_store_list
    clip_card_types
    clip_balance_check
    clip_history
    clip_refresh
    clip_dark          # last: it switches the app to Hebrew
    echo "== done -- now WATCH the clips, do not just check they exist"
}

main "$@"
