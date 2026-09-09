#!/usr/bin/env bash
#
# Drives a HEADLESS emulator to capture the raw screens the Play Store art is built from.
#
# WHY HEADLESS. The emulator's window would take focus on this machine, and a synthetic
# keystroke landing in whatever the user is typing in is not recoverable. `-no-window` plus
# `adb` keeps every input on the device: `adb shell input` posts events into the guest's own
# input pipeline, so nothing on the host is touched. Start it with:
#
#   emulator -avd Pixel_7 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
#
# WHY UIAUTOMATOR RATHER THAN FIXED COORDINATES. Only SearchActivity is exported, so
# `am start` cannot open the other screens, and every screen has to be reached by tapping.
# Hardcoded coordinates rot the moment a row grows a line, so each tap resolves its target
# from a live `uiautomator dump`. When a target genuinely is not there the script says which
# one, rather than tapping empty space and photographing the wrong screen.
#
# ⚠️ A SCREENSHOT IS NOT AN ASSERTION. This can finish clean having photographed an empty
# list, a stale theme or a dialog that never opened. Look at the images.
#
# Usage: tools/capture-shots.sh <out-dir> [demo-backup] [passphrase]
set -euo pipefail

OUTDIR="${1:?usage: capture-shots.sh <out-dir> [demo-backup] [passphrase]}"
BACKUP="${2:-}"
PASSPHRASE="${3:-showcase2026}"
# Preferred over BACKUP when set: a tar of the app data dir, made by snapshot_data().
SNAPSHOT="${SNAPSHOT:-}"

# ⚠️ FIXTURE-DEPENDENT. The detail and store-list shots open one named card, so the label has
# to exist in whatever seed is loaded. Changing the fixture and not this is what turns the
# pass into "not found after scrolling" on a card that was simply renamed. Candidates are
# tried in order, so one script works across seeds.
DETAIL_CARDS=${DETAIL_CARDS:-"Holiday gift 2026|Dinner voucher|Rosh Hashana 2025"}
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG="${PKG:-io.github.naveh92.mycards}"

# Git Bash rewrites anything that looks like a POSIX path into a Windows one, which turns
# /sdcard/ui.xml into C:/Program Files/Git/sdcard/ui.xml inside adb's argument list.
export MSYS_NO_PATHCONV=1

mkdir -p "$OUTDIR"

# --- device plumbing ---------------------------------------------------------------------

# ⚠️ TWO DUMPS INSIDE ~2s FAIL, AND THE FAILURE IMPERSONATES AN APP CRASH. The second one
# comes back "UiAutomationService ... already registered!" and logcat shows a FATAL EXCEPTION
# that looks like the app under test has died. It has not. Since every tap resolves its target
# from a dump, and tap_scroll dumps once per scroll iteration, this loop sat squarely inside
# that window -- so the dumps are spaced here rather than at each call site.
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

# ⚠️ uiautomator dumps the focused window only, so a spinner's drop-down list does NOT
# appear here even though it is on screen. Do not conclude from an empty peek that a
# drop-down failed to open -- look at the capture.
ui_peek() { ui_xml | tr '>' '\n' | grep -oE '(text|resource-id|content-desc)="[^"]+"' | sort -u; }

node_center() {
    ui_xml | tr '>' '\n' | grep -m1 -- "$1" \
        | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' \
        | awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'
}

tap() {
    local c
    # `|| true`: node_center ends in a grep that fails when the node is absent, and under
    # `set -e` a failing command substitution in an assignment kills the script outright --
    # silently, before the message below can say which target was missing.
    c="$(node_center "$1" || true)"
    if [ -z "$c" ]; then echo "!! tap target not found: $1" >&2; return 1; fi
    # shellcheck disable=SC2086
    "$ADB" shell input tap $c
    sleep "${2:-1.5}"
}
tap_maybe() { tap "$@" 2>/dev/null || true; }

# Tap something that may be below the fold.
#
# ⚠️ uiautomator reports the VISIBLE tree, so a row that exists in the list but has not been
# scrolled to simply is not there to match. The wallet shows three of five cards on a 1080p
# screen, and the card this pass wants is the fourth -- which failed as "tap target not
# found" and read exactly like a renamed row.
tap_scroll() {
    local what="$1" i
    for i in $(seq 1 "${2:-6}"); do
        if [ -n "$(node_center "$what" || true)" ]; then
            tap "$what" "${3:-1.5}"
            return 0
        fi
        swipe_up 200
    done
    echo "!! not found after scrolling: $what" >&2
    return 1
}

type_text() { "$ADB" shell input text "$1"; sleep "${2:-1.5}"; }
back()      { "$ADB" shell input keyevent KEYCODE_BACK; sleep "${1:-1}"; }
swipe_up()  { "$ADB" shell input swipe 540 1700 540 500 "${1:-250}"; sleep 0.6; }

# Assert where you are before you shoot.
#
# ⚠️ THIS EXISTS BECAUSE THE PASS ONCE PHOTOGRAPHED THE CARD DETAIL SCREEN TWICE -- once
# correctly, and once under the store-list caption after a stray BACK navigated away -- and
# reported success both times. A screenshot is not an assertion; a log line saying "captured"
# says only that a PNG was written.
expect() {
    if ! ui_peek | grep -q -- "$1"; then
        echo "!! WRONG SCREEN: expected $1 before shooting ${2:-next shot}" >&2
        return 1
    fi
}

shot() {
    sleep 1.2                      # let the ripple and any list fling settle
    "$ADB" shell screencap -p "/sdcard/$1.png"
    "$ADB" pull "/sdcard/$1.png" "$OUTDIR/$1.png" >/dev/null
    "$ADB" shell rm "/sdcard/$1.png"
    echo "  captured $1.png"
}

launch() {
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell am start -n "$PKG/com.mycards.ui.search.SearchActivity" >/dev/null 2>&1
    sleep 3
}

set_theme() {  # light | dark
    [ "$1" = "dark" ] && "$ADB" shell cmd uimode night yes >/dev/null 2>&1 \
                      || "$ADB" shell cmd uimode night no  >/dev/null 2>&1
    sleep 2
}

clear_query() {
    "$ADB" shell input keyevent KEYCODE_MOVE_END
    for _ in $(seq 1 14); do "$ADB" shell input keyevent KEYCODE_DEL; done
    sleep 0.8
}

# ⚠️ THE SOFT KEYBOARD IS THE BIGGEST PROBLEM IN THESE CAPTURES: it covers the bottom 45% of
# the screen, which on the search results is two of the three cards that make the app's point.
#
# Disabling the IME does NOT work, and it fails convincingly: `ime disable` reports success
# and drops the keyboard out of `ime list -s`, but the running LatinIME keeps on serving and
# it respawns after `am force-stop`, so the captures come out with a keyboard on them anyway.
# BACK dismisses it in one press and leaves the screen alone -- and it does not reconfigure
# the user's emulator, which the disable route did.
hide_kb() { "$ADB" shell input keyevent KEYCODE_BACK; sleep 1.2; }

# ⚠️ ORDER MATTERS HERE. Tapping the card-type field opens the drop-down AND raises the
# keyboard, which hides two thirds of the list. BACK closes the DROP-DOWN first and the
# keyboard second, so it takes two -- and then the drop-down has to be reopened from the end
# icon rather than the field, because touching the field raises the keyboard again.
# Open the first card from DETAIL_CARDS that this fixture actually has.
open_detail_card() {
    local label
    while IFS= read -r label; do
        [ -n "$label" ] || continue
        if tap_scroll "text=\"$label\"" 6 2.5 2>/dev/null; then
            echo "   detail card: $label"
            return 0
        fi
        launch    # tap_scroll leaves the wallet scrolled; start the next candidate from the top
    done < <(printf '%s\n' "$DETAIL_CARDS" | tr '|' '\n')
    echo "!! none of these cards are in the fixture: $DETAIL_CARDS" >&2
    return 1
}

open_card_types() {
    tap "resource-id=\"$PKG:id/cardTypeInput\"" 1.5
    hide_kb          # closes the drop-down
    hide_kb          # closes the keyboard
    tap "resource-id=\"$PKG:id/text_input_end_icon\"" 2
}

# --- device setup -------------------------------------------------------------------------

prep_device() {
    echo "== preparing device"

    # A real status bar carries the host clock, a half battery and whatever notifications the
    # emulator has accumulated. Demo mode pins all of it, which is why every shot in the set
    # can show the same 9:30 and a full battery without retouching.
    "$ADB" shell settings put global sysui_demo_allowed 1
    local d="am broadcast -a com.android.systemui.demo -e"
    "$ADB" shell $d command enter                                              >/dev/null
    "$ADB" shell $d command clock -e hhmm 0930                                 >/dev/null
    "$ADB" shell $d command battery -e level 100 -e plugged false              >/dev/null
    "$ADB" shell $d command network -e wifi show -e level 4                    >/dev/null
    "$ADB" shell $d command network -e mobile show -e datatype none -e level 4 >/dev/null
    "$ADB" shell $d command notifications -e visible false                     >/dev/null

    # Mid-flight animation is the main source of a half-drawn capture.
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
        "$ADB" shell settings put global "$s" 0
    done

    # ⚠️ A RUNTIME PERMISSION DIALOG WILL PHOTOGRAPH ITSELF OVER THE APP. The notification
    # prompt appears on first launch after any `pm clear`, and it is a system window, so the
    # app underneath looks fine right up until you open the file. Answer it in advance.
    "$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

    # ⚠️ THE APP LOCALE OUTLIVES THE APP DATA. From Android 13 the per-app language is held by
    # the system LocaleManager, not in shared_prefs, so re-seeding does NOT clear it -- and the
    # last shot of the previous pass deliberately switches to Hebrew. Without this the next
    # pass starts in Hebrew and every English text= tap target silently stops matching.
    "$ADB" shell cmd locale set-app-locales "$PKG" --locales en-US >/dev/null 2>&1 || true

}

# Puts the demo wallet on the device.
#
# TWO ROUTES, AND THE FAST ONE IS THE DEFAULT. `--snapshot` drops a tar of the app's own
# data directory straight in; `--backup` drives the app's real import UI instead.
#
# ⚠️ THE IMPORT ROUTE GOES THROUGH THE SYSTEM FILE PICKER, AND THE PICKER REMEMBERS WHERE IT
# WAS. It reopens in whatever folder it was last left in -- which, once a previous pass has
# been through it, is not Downloads -- so the file the script pushed is not on screen and the
# tap for it finds nothing. Hence force-stopping DocumentsUI first and walking
# Show roots -> Downloads explicitly rather than trusting the opening directory.
#
# The import route is still worth keeping: it exercises the real BackupCodec path end to end,
# which is how a broken R8 keep-rule for BackupPayload would surface here rather than on a
# stranger's new phone. Use it when the snapshot is stale or missing.
seed_data() {
    if [ -n "$SNAPSHOT" ] && [ -f "$SNAPSHOT" ]; then
        seed_from_snapshot
    elif [ -n "$BACKUP" ]; then
        seed_from_backup
    else
        echo "== no seed given; using whatever is already on the device"
    fi
}

# ⚠️ `run-as` can read and write ONLY inside the app sandbox: it cannot reach /sdcard in
# either direction. The first attempt here tarred to /sdcard, which failed with a permission
# error that left a ZERO-BYTE file behind -- and `adb pull` of that empty file still reported
# success. So the tar goes via the app's own cache, and base64 carries it over the shell
# rather than raw bytes, which `adb shell` is free to mangle.
seed_from_snapshot() {
    echo "== seeding demo cards (snapshot)"
    "$ADB" shell am force-stop "$PKG"
    base64 -w0 "$SNAPSHOT" | "$ADB" shell "run-as $PKG sh -c 'base64 -d > cache/appdata.tar'"
    # ⚠️ `rm -rf databases` IS LOAD-BEARING, NOT TIDINESS. SQLite keeps `mycards.db-wal` and
    # `-shm` beside the database; drop a fresh .db next to a previous run's journal and SQLite
    # replays the OLD write-ahead log over it on open. The wallet then shows a mixture of two
    # fixtures, which looks like the seed being wrong rather than stale journal files.
    "$ADB" shell "run-as $PKG sh -c 'cd /data/data/$PKG \
        && rm -rf databases shared_prefs \
        && tar xf cache/appdata.tar \
        && rm cache/appdata.tar'"
    launch
    if ui_peek | grep -q 'text="No cards'; then
        echo "!! snapshot restored but the wallet is empty -- check the tar" >&2
    else
        echo "   restored"
    fi
}

seed_from_backup() {
    echo "== seeding demo cards (import)"
    "$ADB" push "$BACKUP" /sdcard/Download/demo.mycards >/dev/null
    "$ADB" shell am force-stop com.google.android.documentsui
    launch
    tap 'content-desc="More options"'
    tap 'text="Settings"' 2
    swipe_up; swipe_up
    tap "resource-id=\"$PKG:id/importBackup\"" 3
    tap_maybe 'content-desc="Show roots"' 2
    tap_maybe 'text="Downloads"' 2.5
    tap 'text="demo.mycards"' 2.5
    tap "resource-id=\"$PKG:id/passphraseInput\"" 1
    type_text "$PASSPHRASE"
    tap 'text="Restore"' 3
    ui_peek | grep -q 'text="Cards restored"' \
        && echo "   restored" || echo "!! restore did not confirm -- check the device" >&2
    tap_maybe 'text="OK"' 1.5
}

# Snapshots the current wallet so later passes can skip the picker entirely.
snapshot_data() {
    local out="${1:?usage: snapshot_data <out.tar>}"
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell "run-as $PKG sh -c 'cd /data/data/$PKG \
        && tar cf cache/appdata.tar databases shared_prefs'"
    "$ADB" exec-out run-as "$PKG" cat cache/appdata.tar > "$out"
    "$ADB" shell "run-as $PKG rm -f cache/appdata.tar"
    [ -s "$out" ] || { echo "!! snapshot is empty -- run-as could not read the data dir" >&2; return 1; }
    echo "   snapshot $(wc -c < "$out") bytes -> $out"
}

# The freshness line is read when Settings is created, so the sync has to finish and the
# screen has to be re-entered before it says anything but "never".
sync_store_data() {
    echo "== refreshing store lists"
    launch
    tap 'content-desc="More options"'
    tap 'text="Settings"' 2
    tap "resource-id=\"$PKG:id/syncNow\"" 3
    sleep 25
}

# --- the shots ------------------------------------------------------------------------------

main() {
    prep_device
    seed_data
    sync_store_data
    echo "== capturing into $OUTDIR"

    set_theme light

    # 6. The wallet itself: every card, soonest to expire at the top, each with the size of
    #    the shop list behind it.
    launch
    shot "wallet"

    # 1. The question the app exists to answer.
    tap "resource-id=\"$PKG:id/searchInput\"" 1
    type_text "castro"
    hide_kb
    shot "search-store"

    # 3. The same field with the keyboard in the wrong language: "tshsx" is what אדידס comes
    #    out as typed on an English layout, and it still finds adidas. This is the feature
    #    people repeat to someone else, so it earns a slot of its own.
    tap "resource-id=\"$PKG:id/searchInput\"" 1
    clear_query
    type_text "tshsx"
    hide_kb
    shot "hebrew"

    # 5. A card in detail: balance against the original, real purchases, and the freshness of
    #    the shop list it is matched against.
    launch
    open_detail_card
    expect 'text="Spending History"' "detail"
    shot "detail"

    # 2. The other half of the question -- every shop this one card works in. Scrolled past
    #    the head of the list, which is symbols and digits before it reaches any brand.
    # ⚠️ NO hide_kb HERE. Nothing was typed on this screen, so there is no keyboard for BACK
    # to close and it navigates back to the card detail instead -- which photographed the
    # detail screen a second time, under this screen's caption, and the pass reported success.
    tap_scroll 'text="Accepted at' 4 3
    for _ in $(seq 1 8); do swipe_up 200; done
    expect 'resource-id="'"$PKG"':id/storeList"' "store-list"
    shot "store-list"

    # 4. Breadth: the card-type picker is the only screen that shows how many issuers the app
    #    knows, and with the keyboard gone it shows fourteen of them at once.
    #    (The drop-down will not appear in ui_peek -- see the note on ui_peek.)
    launch
    tap 'content-desc="Add a card"' 2.5
    open_card_types
    shot "card-types"

    # 7. Settings, for the freshness line. Cropped from the bottom -- see the anchor note in
    #    StoreShots.java.
    launch
    tap 'content-desc="More options"'
    tap 'text="Settings"' 2
    swipe_up
    shot "refresh"

    # Every purchase across every card, by month. Captured as an alternate rather than a
    # numbered slot: Play caps the set at eight and shot 5 already shows spending inline.
    launch
    tap 'content-desc="Spending history"' 2.5
    shot "history"

    # 8. Dark mode AND a genuinely Hebrew interface. Switching the app language rather than
    #    only the theme is what makes the caption true -- a dark English screen under "Hebrew
    #    and English" is the caption doing work the screenshot does not.
    #
    #    ⚠️ DO THIS LAST. Every text= tap target above is an English string; once the app is
    #    in Hebrew they all stop matching.
    set_theme dark
    launch
    tap 'content-desc="More options"'
    tap 'text="Settings"' 2
    tap "resource-id=\"$PKG:id/langHebrew\"" 3
    launch
    # The wallet rather than a search: a query narrows the list to one card, and this slot
    # has to earn its place on how much Hebrew is on screen, not on the search again.
    shot "dark"

    # Leave the app in English -- prep_device resets the locale too, but not every caller
    # runs it, and a device left in Hebrew makes the next manual poke around confusing.
    "$ADB" shell cmd locale set-app-locales "$PKG" --locales en-US >/dev/null 2>&1 || true
    set_theme light
    echo "== done -- now LOOK at the images in $OUTDIR"
}

main "$@"
