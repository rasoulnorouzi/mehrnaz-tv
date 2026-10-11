#!/usr/bin/env bash
# tools/tv.sh - drive an Android TV (Wi-Fi ADB) or the emulator.
# Run `tools/tv.sh help` for the command list.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- config -----------------------------------------------------------------
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"

PKG="${PKG:-app.mehrnaz.tv}"
ACTIVITY="${ACTIVITY:-app.bolbol.tv.MainActivity}"

TV_IP_FILE="$ROOT/tools/.tv-ip"
TV_IP="${TV_IP:-}"
if [[ -z "$TV_IP" && -f "$TV_IP_FILE" ]]; then
  TV_IP="$(head -n 1 "$TV_IP_FILE" | tr -d '[:space:]' || true)"
fi

SERIAL=""     # chosen lazily by need_device
PERF_BODY=""  # report text accumulated by cmd_perf

# ---- helpers ----------------------------------------------------------------
die() {
  printf 'tv.sh: %s\n' "$*" >&2
  exit 1
}

require_adb() {
  [[ -x "$ADB" ]] || die "adb not found at $ADB (set ANDROID_HOME to your SDK directory)"
}

require_emu() {
  [[ -x "$EMU" ]] || die "emulator not found at $EMU (set ANDROID_HOME, or install the Emulator component)"
}

check_variant() {
  [[ "$1" =~ ^[A-Za-z][A-Za-z0-9]*$ ]] || die "bad build variant '$1' (letters and digits only)"
}

adb_devices() {
  "$ADB" devices 2>/dev/null || true
}

# Prints the serial to target: TV_SERIAL, else TV_IP:5555 if connected,
# else the first online emulator.
serial() {
  local devs emu_serial
  if [[ -n "${TV_SERIAL:-}" ]]; then
    printf '%s\n' "$TV_SERIAL"
    return 0
  fi
  devs="$(adb_devices)"
  if [[ -n "$TV_IP" ]] && awk -v t="$TV_IP:5555" '$1 == t && $2 == "device" { ok = 1 } END { exit !ok }' <<<"$devs"; then
    printf '%s\n' "$TV_IP:5555"
    return 0
  fi
  emu_serial="$(awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }' <<<"$devs")"
  if [[ -n "$emu_serial" ]]; then
    printf '%s\n' "$emu_serial"
    return 0
  fi
  if [[ -n "$TV_IP" ]]; then
    printf 'tv.sh: %s:5555 is not connected (see: tools/tv.sh devices)\n' "$TV_IP" >&2
  fi
  die "No device: run 'tools/tv.sh emu' or 'tools/tv.sh connect'"
}

# Selects the device once. Subcommands that need a device call this first.
need_device() {
  require_adb
  if [[ -z "$SERIAL" ]]; then
    SERIAL="$(serial)" || exit 1
  fi
}

# adb wrapper bound to the selected device.
a() {
  need_device
  "$ADB" -s "$SERIAL" "$@"
}

prop() {
  a shell getprop "$1" | tr -d '\r'
}

is_emulator() {
  [[ "$SERIAL" == emulator-* ]]
}

emit() {
  PERF_BODY+="$*"$'\n'
}

online_emulator() {
  adb_devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }'
}

# ---- help -------------------------------------------------------------------
cmd_help() {
  cat <<'EOF'
Usage: tools/tv.sh <command> [args]

Target device: $TV_SERIAL if set; else TV_IP:5555 when connected (TV_IP env,
or tools/.tv-ip); else the first online emulator.

Setup
  devices               List adb devices (long form)
  connect [ip]          Connect to a TV over Wi-Fi ADB (saves ip to tools/.tv-ip)
  emu [avd]             Start the emulator (default BolbolTV_API29) and wait for boot
  info                  Show model, Android/API level, screen size, density, emulator?

Build and run
  build [variant]       Assemble the app with Gradle (default: release)
  install [variant]     Build, then adb install -r -d the newest APK
  launch                Start the app's main activity and wait for it
  stop                  Force-stop the app

Inspect
  shot <name>           Screenshot to shots/<date>/<name>.png (prints the path)
  logs [n]              Crash buffer, then the last n app log lines (default 200)

Drive
  key <KEY> [n] [delay] Send remote key presses (n default 1, delay default 0.25 s)
                        Common keys: DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT
                        DPAD_CENTER BACK HOME MENU MEDIA_PLAY_PAUSE
                        (KEYCODE_ prefix optional, any case)
  text "<str>"          Type text; spaces become %s. ASCII only (adb limitation).

Measure
  perf [runs]           Cold start, frame and memory report (default 5 runs)
  trace [seconds]       Perfetto system trace (default 10 s), saved to perf/traces

Env: ANDROID_HOME, PKG, ACTIVITY, TV_IP, TV_SERIAL
EOF
}

# ---- setup ------------------------------------------------------------------
cmd_connect() {
  local ip="${1:-}" out state
  require_adb
  if [[ -n "$ip" ]]; then
    [[ "$ip" =~ ^[A-Za-z0-9._-]+$ ]] || die "not a valid IP or host: '$ip'"
    printf '%s\n' "$ip" >"$TV_IP_FILE"
    TV_IP="$ip"
    echo "Saved TV address to tools/.tv-ip: $ip"
  fi
  [[ -n "$TV_IP" ]] || die "No TV IP: run 'tools/tv.sh connect <ip>' (the TV's IP is in its network settings)"

  out="$("$ADB" connect "$TV_IP:5555" 2>&1 || true)"
  printf '%s\n' "$out"
  if ! grep -qE '^(already )?connected to' <<<"$out"; then
    cat >&2 <<EOF
Could not connect to $TV_IP:5555.
On the TV: enable Developer options -> USB/ADB debugging, then run 'tools/tv.sh connect' again
and accept the "Allow ADB debugging" prompt shown on the TV.
EOF
    exit 1
  fi

  state="$(adb_devices | awk -v t="$TV_IP:5555" '$1 == t { print $2; exit }')"
  if [[ "$state" == "device" ]]; then
    echo "Connected: $TV_IP:5555 is ready."
  else
    echo "Connected, but state is '${state:-unknown}'. Accept the ADB prompt on the TV, then run 'tools/tv.sh connect' again." >&2
    exit 1
  fi
}

cmd_emu() {
  local avd="${1:-BolbolTV_API29}" s="" boot="" deadline
  require_adb
  s="$(online_emulator)"
  if [[ -n "$s" ]]; then
    echo "Emulator already online: $s"
    return 0
  fi
  require_emu

  echo "Starting emulator '$avd' (log: /tmp/bolbol-emu.log)..."
  nohup "$EMU" -avd "$avd" -no-boot-anim -no-audio >/tmp/bolbol-emu.log 2>&1 </dev/null &

  deadline=$((SECONDS + 180))
  while (( SECONDS < deadline )); do
    s="$(online_emulator)"
    if [[ -n "$s" ]]; then
      boot="$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
      if [[ "$boot" == "1" ]]; then
        echo "Emulator ready: $s (avd: $avd)"
        return 0
      fi
    fi
    sleep 3
  done
  die "emulator did not finish booting within 180 s (see /tmp/bolbol-emu.log)"
}

cmd_info() {
  need_device
  echo "Serial:    $SERIAL"
  if is_emulator; then
    echo "Emulator:  yes"
  else
    echo "Emulator:  no (physical device)"
  fi
  echo "Model:     $(prop ro.product.model)"
  echo "Android:   $(prop ro.build.version.release) (API $(prop ro.build.version.sdk))"
  echo "Screen:    $(a shell wm size | tr -d '\r')"
  echo "Density:   $(a shell wm density | tr -d '\r')"
}

# ---- build and run ----------------------------------------------------------
cmd_build() {
  local variant="${1:-release}" cap
  check_variant "$variant"
  [[ -x "$ROOT/gradlew" ]] || die "gradlew not found at $ROOT/gradlew"
  cap="${variant^}"
  "$ROOT/tools/gw.sh" ":app:assemble${cap}"
}

cmd_install() {
  local variant="${1:-release}" dir apk out
  check_variant "$variant"
  need_device
  cmd_build "$variant"

  dir="$ROOT/app/build/outputs/apk/$variant"
  apk="$(find "$dir" -type f -name '*.apk' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n 1 | cut -d' ' -f2- || true)"
  [[ -n "$apk" ]] || die "no .apk found under $dir after the build"

  echo "Installing $apk"
  out="$(a install -r -d "$apk" 2>&1 | tr -d '\r' || true)"
  printf '%s\n' "$out"
  grep -q '^Success' <<<"$out" || die "adb install did not report Success"
}

cmd_launch() {
  local out
  need_device
  out="$(a shell am start -W -n "$PKG/$ACTIVITY" 2>&1 | tr -d '\r' || true)"
  printf '%s\n' "$out"
  if grep -q '^Error' <<<"$out"; then
    die "launch failed (is $PKG installed, and is ACTIVITY right?)"
  fi
}

cmd_stop() {
  need_device
  a shell am force-stop "$PKG"
  echo "Stopped $PKG"
}

# ---- inspect ----------------------------------------------------------------
cmd_shot() {
  local name="${1:-}" dir file n=2
  [[ -n "$name" ]] || die "usage: tools/tv.sh shot <name>"
  [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || die "shot name may only contain letters, digits, '.', '_' and '-'"
  need_device

  dir="$ROOT/shots/$(date +%F)"
  mkdir -p "$dir"
  file="$dir/$name.png"
  while [[ -e "$file" ]]; do
    file="$dir/$name-$n.png"
    n=$((n + 1))
  done

  if ! a exec-out screencap -p >"$file" || [[ ! -s "$file" ]]; then
    rm -f "$file"
    die "screenshot failed or came back empty (is the screen on?)"
  fi
  printf '%s\n' "$file"
}

cmd_logs() {
  local n="${1:-200}" pid
  [[ "$n" =~ ^[0-9]+$ ]] || die "usage: tools/tv.sh logs [n]"
  need_device

  echo "=== crash buffer ==="
  a logcat -d -b crash 2>/dev/null | tr -d '\r' || true

  pid="$(a shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{ print $1; exit }' || true)"
  if [[ -n "$pid" ]]; then
    echo "=== last $n lines (pid $pid) ==="
    a logcat -d -v time --pid="$pid" 2>/dev/null | tr -d '\r' | tail -n "$n" || true
  else
    echo "=== last $n lines (app not running, unfiltered) ==="
    a logcat -d -v time 2>/dev/null | tr -d '\r' | tail -n "$n" || true
  fi
}

# ---- drive ------------------------------------------------------------------
cmd_key() {
  local key="${1:-}" n="${2:-1}" delay="${3:-0.25}" i
  [[ -n "$key" ]] || die "usage: tools/tv.sh key <KEY> [n] [delay]"
  key="${key^^}"
  key="${key#KEYCODE_}"
  [[ "$key" =~ ^[A-Z0-9_]+$ ]] || die "bad key name: ${1}"
  [[ "$n" =~ ^[1-9][0-9]*$ ]] || die "n must be a positive integer"
  [[ "$delay" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "delay must be a number of seconds, e.g. 0.25"
  need_device

  for (( i = 1; i <= n; i++ )); do
    a shell input keyevent "KEYCODE_$key"
    if (( i < n )); then
      sleep "$delay"
    fi
  done
}

cmd_text() {
  local str="${1:-}" esc
  [[ -n "$str" ]] || die 'usage: tools/tv.sh text "<str>"'
  if LC_ALL=C grep -q '[^ -~]' <<<"$str"; then
    die "non-ASCII text is not supported by 'input text' (adb limitation)"
  fi
  need_device

  # Spaces become %s (input text's space token); shell-special characters are escaped.
  esc="$(printf '%s' "$str" | sed -e 's| |%s|g' -e 's|[^A-Za-z0-9_.,@:/=+%-]|\\&|g')"
  a shell input text "$esc"
}

# ---- measure ----------------------------------------------------------------
cmd_perf() {
  local runs="${1:-5}" i row k line out ms median max commit stamp report
  local gfx frames mem total_pss summary heaps
  local -a totals=()
  [[ "$runs" =~ ^[1-9][0-9]*$ ]] || die "usage: tools/tv.sh perf [runs]"
  need_device

  commit="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo nogit)"
  stamp="$(date +%F)"
  report="$ROOT/perf/reports/$stamp-$commit.md"

  PERF_BODY=""
  emit "# Perf report $stamp"
  emit ""
  emit "- Date: $(date '+%F %T')"
  emit "- Device: $(prop ro.product.model)"
  emit "- Android: $(prop ro.build.version.release)"
  emit "- Serial: $SERIAL"
  emit "- App: $PKG/$ACTIVITY"
  emit "- Commit: $commit"
  if is_emulator; then
    emit ""
    emit "**EMULATOR: numbers not valid for budgets**"
  fi

  # 1. Cold start
  emit ""
  emit "## Cold start ($runs runs, am start -W)"
  emit ""
  for (( i = 1; i <= runs; i++ )); do
    a shell am force-stop "$PKG"
    sleep 1
    out="$(a shell am start -W -n "$PKG/$ACTIVITY" 2>&1 | tr -d '\r' || true)"
    ms="$(awk -F': *' '/^TotalTime:/ { print $2; exit }' <<<"$out")"
    [[ "$ms" =~ ^[0-9]+$ ]] || die "run $i: no TotalTime in 'am start' output (is $PKG installed and ACTIVITY correct?)"
    totals+=("$ms")
    emit "- Run $i: $ms ms"
  done
  median="$(printf '%s\n' "${totals[@]}" | sort -n | awk '{ a[NR] = $1 } END { if (NR % 2) print a[(NR + 1) / 2]; else print int((a[NR / 2] + a[NR / 2 + 1]) / 2) }')"
  max="$(printf '%s\n' "${totals[@]}" | sort -n | tail -n 1)"
  emit "- Median: $median ms"
  emit "- Max: $max ms"

  # 2. Frames (scripted scroll)
  emit ""
  emit "## Frames (scripted scroll: 3 rows of 8x LEFT, 8x RIGHT, 1x DOWN)"
  emit ""
  a shell dumpsys gfxinfo "$PKG" reset >/dev/null
  for (( row = 0; row < 3; row++ )); do
    for (( k = 0; k < 8; k++ )); do
      a shell input keyevent KEYCODE_DPAD_LEFT
      sleep 0.15
    done
    for (( k = 0; k < 8; k++ )); do
      a shell input keyevent KEYCODE_DPAD_RIGHT
      sleep 0.15
    done
    a shell input keyevent KEYCODE_DPAD_DOWN
    sleep 0.15
  done
  gfx="$(a shell dumpsys gfxinfo "$PKG" 2>/dev/null | tr -d '\r' || true)"
  frames="$(grep -E '^[[:space:]]*(Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile):' <<<"$gfx" | sed 's/^[[:space:]]*//' || true)"
  if [[ -n "$frames" ]]; then
    while IFS= read -r line; do
      emit "- $line"
    done <<<"$frames"
  else
    emit "- no gfxinfo frame stats (is $PKG in the foreground?)"
  fi

  # 3. Memory
  emit ""
  emit "## Memory (dumpsys meminfo)"
  emit ""
  mem="$(a shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' || true)"
  # Newer format: "TOTAL PSS: 123". Table format: a "TOTAL" row whose first number is PSS.
  total_pss="$(sed -n -e 's/.*TOTAL PSS:[[:space:]]*\([0-9][0-9]*\).*/\1/p' -e 's/^[[:space:]]*TOTAL[[:space:]][[:space:]]*\([0-9][0-9]*\).*/\1/p' <<<"$mem" | head -n 1 || true)"
  if [[ -n "$total_pss" ]]; then
    emit "- TOTAL PSS: $total_pss KB"
  else
    emit "- TOTAL PSS: n/a (could not parse dumpsys meminfo)"
  fi
  summary="$(awk '/App Summary/ { f = 1 } f' <<<"$mem")"
  [[ -n "$summary" ]] || summary="$mem"
  heaps="$(sed -n -E 's/^[[:space:]]*(Java Heap|Native Heap|Graphics):?[[:space:]]+([0-9]+).*/\1: \2 KB/p' <<<"$summary" | head -n 3 || true)"
  if [[ -n "$heaps" ]]; then
    while IFS= read -r line; do
      emit "- $line"
    done <<<"$heaps"
  else
    emit "- heap breakdown: n/a"
  fi

  mkdir -p "$ROOT/perf/reports"
  printf '%s' "$PERF_BODY" >"$report"
  echo "Report: $report"
  cat "$report"
}

cmd_trace() {
  local secs="${1:-10}" stamp dest remote="/data/misc/perfetto-traces/bolbol.perfetto-trace"
  [[ "$secs" =~ ^[1-9][0-9]*$ ]] || die "usage: tools/tv.sh trace [seconds]"
  need_device

  a shell setprop persist.traced.enable 1

  stamp="$(date +%F-%H%M%S)"
  mkdir -p "$ROOT/perf/traces"
  dest="$ROOT/perf/traces/$stamp.perfetto-trace"

  cat <<EOF | a shell perfetto -c - --txt -o "$remote"
buffers {
  size_kb: 32768
}
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "power/suspend_resume"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "dalvik"
      atrace_apps: "$PKG"
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
  }
}
duration_ms: $((secs * 1000))
EOF

  a pull "$remote" "$dest"
  [[ -s "$dest" ]] || die "trace pull produced an empty file: $dest"
  echo "Trace: $dest"
  echo "Open at https://ui.perfetto.dev (drag the file in)"
}

# ---- dispatch ---------------------------------------------------------------
main() {
  local cmd="${1:-help}"
  if (( $# > 0 )); then
    shift
  fi
  case "$cmd" in
    help|-h|--help) cmd_help ;;
    devices)        require_adb; "$ADB" devices -l ;;
    connect)        cmd_connect "$@" ;;
    emu)            cmd_emu "$@" ;;
    info)           cmd_info ;;
    build)          cmd_build "$@" ;;
    install)        cmd_install "$@" ;;
    launch)         cmd_launch ;;
    stop)           cmd_stop ;;
    shot)           cmd_shot "$@" ;;
    key)            cmd_key "$@" ;;
    text)           cmd_text "$@" ;;
    logs)           cmd_logs "$@" ;;
    perf)           cmd_perf "$@" ;;
    trace)          cmd_trace "$@" ;;
    *)
      printf 'tv.sh: unknown command: %s\n\n' "$cmd" >&2
      cmd_help >&2
      exit 2
      ;;
  esac
}

main "$@"
