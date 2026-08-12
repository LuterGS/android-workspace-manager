#!/usr/bin/env bash
# tile.sh — ADB-based tiling window manager for Android 16+
# Proof of concept. Requires: adb, freeform support enabled.
#
# Usage:
#   tile.sh setup          — Enable required settings on device
#   tile.sh launch <pkg>   — Launch app in freeform mode
#   tile.sh tile            — Tile all freeform tasks in master+stack layout
#   tile.sh list           — List visible freeform tasks
#   tile.sh layout <name>  — Switch layout (master-stack, columns, monocle)
#   tile.sh close <taskId> — Close a freeform task
#   tile.sh reset          — Return all tasks to fullscreen
#   tile.sh screenshot     — Take screenshot and pull to /tmp/

set -euo pipefail

# --- Config ---
LAYOUT="${TILE_LAYOUT:-master-stack}"
MASTER_RATIO="${TILE_MASTER_RATIO:-55}"  # percent of screen width for master
FORCE_LANDSCAPE="${TILE_FORCE_LANDSCAPE:-0}"  # 1 = rotate to landscape before tiling
GAP="${TILE_GAP:-0}"                      # pixels between adjacent windows
OUTER_GAP="${TILE_OUTER_GAP:-$GAP}"       # pixels between a window and the screen edge
STATUS_BAR_HEIGHT=100                     # pixels, adjust per device
NAV_BAR_HEIGHT=100                        # pixels, adjust per device
STATE_FILE="/tmp/.tile_managed_pkgs"      # tracks packages we launched

# --- Helpers ---

die() { echo "ERROR: $*" >&2; exit 1; }

# macOS ships BSD grep (no -P) — find a PCRE-capable grep instead.
if echo x | grep -qP x 2>/dev/null; then
    GREP=grep
elif command -v ggrep >/dev/null 2>&1; then
    GREP=ggrep
else
    die "No PCRE-capable grep found. On macOS: brew install grep"
fi

require_adb() {
    command -v adb >/dev/null 2>&1 || die "adb not found in PATH"
    local devices
    devices=$(adb devices | grep -c 'device$')
    [[ "$devices" -ge 1 ]] || die "No ADB device connected"
}

get_screen_size() {
    # `wm size` reports the panel's natural orientation (e.g. 2160x1584 on a
    # folded-open Fold) regardless of the current rotation. The authoritative
    # current logical size is `app=` on display 0 in `dumpsys window displays`.
    local size
    size=$(adb shell dumpsys window displays | \
        "$GREP" -A1 'mDisplayId=0' | "$GREP" -oP 'app=\K\d+x\d+' | head -1)
    [[ -n "$size" ]] || size=$(adb shell wm size | "$GREP" -oP '\d+x\d+' | tail -1)
    SCREEN_W="${size%x*}"
    SCREEN_H="${size#*x}"
}

_dump_activities() {
    # Cache dumpsys output for the duration of a command to avoid repeated calls
    if [[ -z "${_DUMPSYS_CACHE:-}" ]]; then
        _DUMPSYS_CACHE=$(adb shell "dumpsys activity activities" 2>/dev/null)
    fi
    echo "$_DUMPSYS_CACHE"
}

get_freeform_tasks() {
    # Returns unique task IDs for all freeform tasks
    _dump_activities | \
        "$GREP" -oP '^\s+\* Task\{[^ ]+ #\K\d+(?= .* mode=freeform)' | sort -u || true
}

get_task_info() {
    local task_id="$1"
    _dump_activities | \
        grep -A3 "^\s\+\* Task{.*#${task_id} " | head -4
}

get_all_visible_tasks() {
    # Returns unique task IDs for visible freeform tasks
    _dump_activities | \
        "$GREP" -P '^\s+\* Task\{.*visible=true.*mode=freeform' | \
        "$GREP" -oP '#\K\d+' | sort -u || true
}

# --- Commands ---

cmd_setup() {
    echo "Enabling freeform windowing on device..."
    adb shell settings put global enable_freeform_support 1
    adb shell settings put global force_resizable_activities 1
    adb shell settings put global enable_non_resizable_multi_window 1
    # NOTE: desktop_mode and force_desktop_mode_on_external_displays intentionally
    # omitted — they lock Pixel Launcher to landscape and aren't needed for
    # freeform windowing on the phone's primary display.
    echo "Done. Freeform windowing enabled."
    echo ""
    echo "Verify with: adb shell settings get global enable_freeform_support"
}

cmd_launch() {
    local pkg="${1:-}"
    [[ -n "$pkg" ]] || die "Usage: tile.sh launch <package.name>"

    # Resolve the main activity
    local activity
    activity=$(adb shell cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
    [[ -n "$activity" ]] || die "Could not resolve activity for $pkg"

    echo "Launching $activity in freeform mode..."
    adb shell am start -n "$activity" --windowingMode 5 -f 0x10000000
    # Track this package for restore
    grep -qxF "$pkg" "$STATE_FILE" 2>/dev/null || echo "$pkg" >> "$STATE_FILE"
    echo "Launched. Use 'tile.sh tile' to arrange windows."
}

cmd_restore() {
    # Bring back all previously-launched freeform windows after home/recents.
    # Uses the state file to know which packages we manage.
    [[ -f "$STATE_FILE" ]] || die "No managed windows. Launch some first with: tile.sh launch <pkg>"

    local count=0
    while IFS= read -r pkg; do
        [[ -n "$pkg" ]] || continue
        local activity
        activity=$(adb shell cmd package resolve-activity --brief "$pkg" 2>/dev/null | tail -1)
        if [[ -n "$activity" ]]; then
            adb shell am start -n "$activity" --windowingMode 5 -f 0x10000000 >/dev/null 2>&1
            echo "  Restored: $pkg"
            ((count++))
        fi
    done < "$STATE_FILE"

    echo "Restored $count windows."
    sleep 0.5
    echo "Re-tiling..."
    _DUMPSYS_CACHE=""
    cmd_tile
}

cmd_list() {
    _DUMPSYS_CACHE=""  # force fresh dump
    echo "Freeform tasks:"
    echo "---"
    local seen=""
    _dump_activities | \
        "$GREP" -P '^\s+\* Task\{.*mode=freeform' | \
        while read -r line; do
            local tid
            tid=$(echo "$line" | "$GREP" -oP '#\K\d+')
            # Deduplicate (tasks appear in multiple dumpsys sections)
            echo "$seen" | grep -qw "$tid" && continue
            seen="$seen $tid"
            local pkg
            pkg=$(echo "$line" | "$GREP" -oP 'A=\d+:\K[^ ]+')
            local vis
            vis=$(echo "$line" | "$GREP" -oP 'visible=\K\w+')
            echo "  Task #$tid  $pkg  visible=$vis"
        done
    echo "---"
}

cmd_tile() {
    _DUMPSYS_CACHE=""  # force fresh dump
    # Forcing landscape only helps on tall, narrow phones. Foldables ignore
    # user_rotation on the inner display (One UI overrides it), and their inner
    # screen is wide enough to tile in portrait — so this is opt-in.
    # Either way we read the real logical size afterwards.
    if [[ "$FORCE_LANDSCAPE" == "1" ]]; then
        adb shell settings put system accelerometer_rotation 0
        adb shell settings put system user_rotation 1  # 1 = landscape
        sleep 0.5
    fi
    get_screen_size
    # Read task IDs into array
    local -a task_arr=()
    while IFS= read -r tid; do
        [[ -n "$tid" ]] && task_arr+=("$tid")
    done < <(get_all_visible_tasks)
    local count=${#task_arr[@]}

    [[ "$count" -gt 0 ]] || die "No visible freeform tasks. Launch some first with: tile.sh launch <pkg>"

    local top=$STATUS_BAR_HEIGHT
    local bottom=$((SCREEN_H - NAV_BAR_HEIGHT))

    echo "Tiling $count windows (layout: $LAYOUT, screen: ${SCREEN_W}x${SCREEN_H})"

    case "$LAYOUT" in
        master-stack)
            _layout_master_stack "$count" "$top" "$bottom" "${task_arr[@]}"
            ;;
        columns)
            _layout_columns "$count" "$top" "$bottom" "${task_arr[@]}"
            ;;
        monocle)
            _layout_monocle "$count" "$top" "$bottom" "${task_arr[@]}"
            ;;
        *)
            die "Unknown layout: $LAYOUT. Options: master-stack, columns, monocle"
            ;;
    esac

    echo "Done."
}

# TODO(you): shrink a window's bounds so tiled windows don't touch.
#
#   Called as: _apply_gap <left> <top> <right> <bottom>
#   Must echo the adjusted bounds as "left top right bottom".
#
# Two decisions shape how this feels:
#   1. One uniform gap, or separate OUTER_GAP (window touches a screen edge)
#      and INNER_GAP (window borders another window)? i3 distinguishes them.
#      With a single gap the screen edge ends up looking half as wide as the
#      space between neighbours, because only one window shrinks at an edge
#      while two shrink at a shared border.
#   2. If you shrink every side by the full gap, adjacent windows each give up
#      that much and the visible gap between them doubles. Halving it evens
#      the spacing out, but then the outer edge needs its own handling.
#
# GAP/OUTER_GAP are read from TILE_GAP/TILE_OUTER_GAP at the top of the script.
_apply_gap() {
    local left="$1" top="$2" right="$3" bottom="$4"
    echo "$left $top $right $bottom"
}

# Single choke point for window placement, so gaps apply to every layout.
_resize() {
    local tid="$1"
    local bounds
    bounds=$(_apply_gap "$2" "$3" "$4" "$5")
    adb shell am task resize "$tid" $bounds
}

_layout_master_stack() {
    # bash 3.2 (macOS) has no `local -n` — take the array as trailing args.
    local count="$1" top="$2" bottom="$3"
    shift 3
    local -a _tasks=("$@")

    if [[ "$count" -eq 1 ]]; then
        _resize "${_tasks[0]}" 0 "$top" "$SCREEN_W" "$bottom"
        echo "  Task #${_tasks[0]} → fullscreen"
        return
    fi

    local master_w=$((SCREEN_W * MASTER_RATIO / 100))
    local stack_count=$((count - 1))
    local stack_h=$(( (bottom - top) / stack_count ))

    for i in "${!_tasks[@]}"; do
        local tid="${_tasks[$i]}"
        if [[ "$i" -eq 0 ]]; then
            _resize "$tid" 0 "$top" "$master_w" "$bottom"
            echo "  Task #$tid → master (0,$top → $master_w,$bottom)"
        else
            local slot=$((i - 1))
            local s_top=$((top + slot * stack_h))
            local s_bottom=$((s_top + stack_h))
            [[ "$i" -eq "$((count - 1))" ]] && s_bottom=$bottom
            _resize "$tid" "$master_w" "$s_top" "$SCREEN_W" "$s_bottom"
            echo "  Task #$tid → stack ($master_w,$s_top → $SCREEN_W,$s_bottom)"
        fi
    done
}

_layout_columns() {
    local count="$1" top="$2" bottom="$3"
    shift 3
    local -a _tasks=("$@")
    local col_w=$((SCREEN_W / count))

    for i in "${!_tasks[@]}"; do
        local tid="${_tasks[$i]}"
        local left=$((i * col_w))
        local right=$((left + col_w))
        [[ "$i" -eq "$((count - 1))" ]] && right=$SCREEN_W
        _resize "$tid" "$left" "$top" "$right" "$bottom"
        echo "  Task #$tid → column ($left,$top → $right,$bottom)"
    done
}

_layout_monocle() {
    local count="$1" top="$2" bottom="$3"
    shift 3
    local -a _tasks=("$@")

    for i in "${!_tasks[@]}"; do
        local tid="${_tasks[$i]}"
        _resize "$tid" 0 "$top" "$SCREEN_W" "$bottom"
        echo "  Task #$tid → fullscreen (monocle)"
    done

    # Bring last task to front
    local focused="${_tasks[$((count - 1))]}"
    if [[ -n "$focused" ]]; then
        adb shell am task focus "$focused" 2>/dev/null || true
        echo "  Focused: Task #$focused"
    fi
}

cmd_layout() {
    local name="${1:-}"
    [[ -n "$name" ]] || die "Usage: tile.sh layout <master-stack|columns|monocle>"
    LAYOUT="$name"
    cmd_tile
}

cmd_close() {
    local tid="${1:-}"
    [[ -n "$tid" ]] || die "Usage: tile.sh close <taskId>"
    adb shell am task remove "$tid"
    echo "Closed task #$tid"
    # Re-tile remaining
    sleep 0.5
    cmd_tile 2>/dev/null || true
}

cmd_reset() {
    _DUMPSYS_CACHE=""
    echo "Returning all freeform tasks to fullscreen..."
    local tasks
    tasks=$(get_freeform_tasks)
    while IFS= read -r tid; do
        [[ -n "$tid" ]] || continue
        get_screen_size
        # Deliberately not _resize: restoring fullscreen must ignore gaps.
        adb shell am task resize "$tid" 0 0 "$SCREEN_W" "$SCREEN_H"
        echo "  Task #$tid → fullscreen"
    done <<< "$tasks"
    # Restore portrait + auto-rotate
    adb shell settings put system user_rotation 0
    adb shell settings put system accelerometer_rotation 1
    rm -f "$STATE_FILE"
    echo "Done. Rotation restored to auto."
}

cmd_screenshot() {
    local ts
    ts=$(date +%Y%m%d_%H%M%S)
    local path="/tmp/tile_screenshot_${ts}.png"
    adb shell screencap -p /sdcard/tile_screenshot.png
    adb pull /sdcard/tile_screenshot.png "$path" 2>/dev/null
    adb shell rm /sdcard/tile_screenshot.png
    echo "Screenshot saved to $path"
}

cmd_watch() {
    local interval="${1:-2}"
    echo "Watching for changes every ${interval}s (Ctrl+C to stop)..."
    local prev_state=""
    while true; do
        _DUMPSYS_CACHE=""  # force fresh dump each loop
        local cur_state
        cur_state=$(get_all_visible_tasks | tr '\n' ' ')
        local cur_size
        cur_size=$(adb shell wm size | "$GREP" -oP '\d+x\d+' | tail -1)
        local state_key="${cur_state}|${cur_size}"
        if [[ "$state_key" != "$prev_state" ]]; then
            echo "[$(date +%H:%M:%S)] Change detected — retiling..."
            cmd_tile 2>/dev/null || echo "  (no windows to tile)"
            prev_state="$state_key"
        fi
        sleep "$interval"
    done
}

# --- Main ---

require_adb

case "${1:-help}" in
    setup)      cmd_setup ;;
    restore)    cmd_restore ;;
    launch)     shift; cmd_launch "$@" ;;
    list)       cmd_list ;;
    tile)       cmd_tile ;;
    layout)     shift; cmd_layout "$@" ;;
    close)      shift; cmd_close "$@" ;;
    reset)      cmd_reset ;;
    screenshot) cmd_screenshot ;;
    watch)      shift; cmd_watch "$@" ;;
    help|*)
        echo "tile.sh — ADB-based tiling window manager for Android 16+"
        echo ""
        echo "Commands:"
        echo "  setup              Enable freeform windowing on device"
        echo "  restore            Bring back freeform windows after home/recents"
        echo "  launch <pkg>       Launch app in freeform mode"
        echo "  tile               Tile all freeform windows"
        echo "  list               List visible freeform tasks"
        echo "  layout <name>      Switch layout (master-stack, columns, monocle)"
        echo "  close <taskId>     Close a task and re-tile"
        echo "  reset              Return all tasks to fullscreen"
        echo "  screenshot         Capture and pull screenshot"
        echo "  watch [secs]       Auto-retile on window/rotation changes (default: 2s)"
        echo ""
        echo "Environment:"
        echo "  TILE_LAYOUT        Default layout (default: master-stack)"
        echo "  TILE_MASTER_RATIO  Master pane width % (default: 55)"
        echo ""
        echo "Example:"
        echo "  tile.sh setup"
        echo "  tile.sh launch com.android.settings"
        echo "  tile.sh launch com.android.chrome"
        echo "  tile.sh launch com.google.android.apps.messaging"
        echo "  tile.sh tile"
        echo "  tile.sh layout columns"
        ;;
esac
