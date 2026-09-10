#!/usr/bin/env bash
#
# Records the screen clips that play inside the phone in the promo video.
#
# Same headless emulator and the same uiautomator navigation as tools/capture-shots.sh --
# read that file first; the device plumbing is explained there and duplicated here only so
# each script runs on its own.
#
# ⚠️ NO KEYBOARD IN ANY SCENE. This used to say the opposite -- that watching the letters go
# in was worth the keyboard covering the bottom 45% of the screen. It is not: on the search
# results that 45% is two of the three cards that make the app's point, and unlike a still,
# a clip holds the same crop for its whole eight seconds. See type_query for how the query
# gets in without ever focusing the field.
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

# Puts a query in the search field one character at a time, WITHOUT EVER RAISING THE
# KEYBOARD -- and without touching the field at all.
#
# ⚠️ EVERY OTHER WAY OF TYPING RAISES THE IME. Measured on this emulator, all of these end
# with `dumpsys input_method` reporting mInputShown=true and a Gboard on the capture:
#
#   * `adb shell input text` after hiding the keyboard with ESCAPE -- the editor asks for the
#     IME again on the first key it receives.
#   * the same, with the field focused by D-pad rather than by touch, so the IME was never
#     shown in the first place. Focusing that way genuinely keeps it down; the first
#     character brings it up.
#   * the emulator console's `event text`, which arrives as a hardware key. Same result.
#   * `ime disable` on Gboard: the voice IME takes over and draws a full "Tap to speak"
#     panel, which is worse. Disable that one too and the system re-enables Gboard.
#   * `sendevent` straight onto the guest's own qwerty2 keyboard device: permission denied,
#     and `adb root` is refused on a production build.
#
# WHAT WORKS. SearchActivity is exported, singleTop, and already reads its query from an
# intent extra (SearchActivity.EXTRA_QUERY, "query") for the notification tap. So each am
# start lands in onNewIntent -> searchInput.setText on a field that never had focus, and
# nothing ever asks for an IME. One growing prefix per step is what makes the list narrow on
# screen the way typing did.
#
# ⚠️ THE FIELD MUST NOT BE FOCUSED WHEN THIS RUNS. Do not tap searchInput first "to get the
# cursor in there": that raises the keyboard by itself, before a single character is sent.
type_query() {
    local s="$1" i
    for i in $(seq 1 ${#s}); do
        "$ADB" shell am start -n "$PKG/com.mycards.ui.search.SearchActivity" \
            --es query "${s:0:i}" >/dev/null 2>&1
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

    # ⚠️ screenrecord EMITS A FRAME ONLY WHEN THE SCREEN CHANGES, so a clip's duration ends at
    # its last visible change rather than when the recorder was stopped -- and it starts at
    # the first one, not when it was started. The 4.5s hold on the search results is a still
    # screen, so it produced no frames at all: search-store came back 26 frames over 6.3s for
    # an 8s scene, and make-video would have looped it, restarting the typing halfway through
    # the scene. (This is also why a clip can be shorter than the wall-clock time it took.)
    #
    # So clone the last frame out far enough to cover any scene. A scene that ends on a still
    # screen is what these were going to be anyway -- the hold on the finished search is the
    # point of it -- and a clip that scrolls is already longer than its scene, so it never
    # reaches the padding. fps=30 also turns screenrecord's variable rate into the constant
    # one make-video's filters expect.
    ffmpeg -y -loglevel error -i "$OUTDIR/$name.mp4" \
        -vf "fps=30,tpad=stop_mode=clone:stop_duration=6" \
        -c:v libx264 -preset veryfast -crf 20 "$OUTDIR/$name.cfr.mp4"
    mv "$OUTDIR/$name.cfr.mp4" "$OUTDIR/$name.mp4"

    printf "  %-16s %s KB  %ss (%s raw)\n" "$name.mp4" \
        "$(( $(wc -c < "$OUTDIR/$name.mp4") / 1024 ))" \
        "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUTDIR/$name.mp4" 2>/dev/null)" \
        "$sz"
}

# --- the clips --------------------------------------------------------------------------------

clip_search_store() {
    launch
    start_rec
    sleep 0.8
    type_query "castro"
    sleep 4.5          # hold on the results: this scene is 8s and the list is the point
    stop_rec "search-store"
}

clip_hebrew() {
    launch
    start_rec
    sleep 0.8
    type_query "tshsx" 0.50
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
    tap "resource-id=\"$PKG:id/text_input_end_icon\"" 2.5
    # ⚠️ PRE-SCROLL SIX ROWS, NOT ALL EIGHTEEN -- THIS IS WHY THE 1.3 CUT WAS FROZEN.
    #
    # The list is 32 types and fourteen fit on screen, so it has exactly 18 rows of travel and
    # no more. The old pre-scroll was three flicks, and a flick with its fling is worth about
    # nine rows: two of them and the list is already sitting on its last row. The three swipes
    # that then ran while the recorder was on had nowhere left to go, so the shipped 1.3 scene
    # is five seconds of one motionless screenful -- which is what it was reported as. Nothing
    # was wrong with the swipes, the recorder or the popup; the list was simply at the bottom
    # before the camera started.
    #
    # Two slow drags is six rows, which leaves twelve for the scene itself.
    for _ in 1 2; do "$ADB" shell input swipe 540 1750 540 1350 1000; sleep 0.1; done
    sleep 1.5

    # ONE CONTINUOUS DRAG, NOT A SERIES OF THEM. `input swipe` interpolates over its whole
    # duration, so one long drag is a single smooth movement. Repeated shorter drags cost an
    # adb round trip each -- roughly 0.7s of dead still list between them -- and the scroll
    # visibly stutters. 1450px is the twelve rows the pre-scroll left, and 6.5s over them is
    # about two rows a second: slow enough to read a name as it goes past, which is the point
    # of the scene.
    #
    # ⚠️ START THE DRAG BEFORE THE RECORDER, AND IN THE BACKGROUND. start_rec spends 2.5s
    # waiting for screenrecord to come up, and how much of that wait lands in the file varies
    # from run to run -- one take here opened with 2.4 dead seconds, which at a 5s scene is
    # half of it spent on a motionless list. Beginning the drag first means whenever the
    # capture actually starts, it starts on a list that is already moving.
    #
    # ⚠️ CHECK THE CLIP, NOT THE SCRIPT. `ffmpeg -ss N -i card-types.mp4 -frames:v 1` a second
    # apart has to show DIFFERENT rows, at N=0 as well as at N=4. That is the check the 1.3
    # cut did not get.
    "$ADB" shell input swipe 540 2050 540 600 6500 &
    local swipe=$!
    start_rec
    sleep 3.4
    stop_rec "card-types"
    wait "$swipe" 2>/dev/null || true
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

# No scene types any more (see type_query), so nothing here should ever raise a keyboard.
# This stays as the belt to that braces: if an earlier pass left Gboard in floating mode --
# which a stray ESCAPE or a long-press during automation can do -- it stops docking and
# leaves a vertical pill of mic/backspace/search/emoji icons hovering over the middle of the
# app, and it would hover there over any scene, typing or not. It survives `am force-stop`
# and `ime reset` because it is a saved Gboard preference, so the only thing that clears it
# is clearing Gboard's own data. Cheap, and it is a keyboard on an emulator.
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
