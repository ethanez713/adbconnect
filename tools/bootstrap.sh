#!/usr/bin/env bash
# tools/bootstrap.sh — one-shot setup, run while adb IS connected to the phone.
#
# Installs the APK, grants WRITE_SECURE_SETTINGS and TURN_SCREEN_ON (only possible over an adb
# connection that already works), starts the app so its foreground service is
# live, and pulls the auth token off the device so tools/adbwake never needs
# the user to type or copy it by hand.
set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID="com.ezhou.adbconnect"
APK="app/build/outputs/apk/debug/app-debug.apk"
CONFIG_DIR="$HOME/.config"
CONFIG_FILE="$CONFIG_DIR/adbconnect"
TOKEN_FILE="$CONFIG_DIR/adbconnect-token"

log() { printf '>> %s\n' "$*"; }
err() { printf '!! %s\n' "$*" >&2; }

# Symlinked, not copied: an installed copy that drifts from the repo one is the same
# class of bug as the two state files that could disagree about the phone's IP.
log "linking tools into ~/.local/bin"
mkdir -p "$HOME/.local/bin"
ln -sfn "$PWD/tools/aconnect" "$HOME/.local/bin/aconnect"
ln -sfn "$PWD/tools/adbwake" "$HOME/.local/bin/adbwake"
ln -sfn "$PWD/tools/adbpush" "$HOME/.local/bin/adbpush"

command -v adb >/dev/null || { err "adb not on PATH -- source ~/android-tooling/env.sh first"; exit 1; }

# An emulator is frequently attached alongside the phone on this box; an
# untargeted adb command against more than one device/emulator just errors out,
# so every call below is pinned to this SERIAL explicitly.
SERIAL="$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
[ -n "$SERIAL" ] || { err "no non-emulator device in 'adb devices'"; exit 1; }
log "targeting device $SERIAL"

[ -f "$APK" ] || { err "$APK not found -- run ./gradlew assembleDebug first"; exit 1; }

log "installing $APK"
adb -s "$SERIAL" install -r "$APK"

log "granting WRITE_SECURE_SETTINGS"
adb -s "$SERIAL" shell pm grant "$APP_ID" android.permission.WRITE_SECURE_SETTINGS

# The pushed-update notification is how adbpush surfaces its one-tap install, and the app
# deliberately never asks for POST_NOTIFICATIONS at runtime (that is what keeps its own
# foreground-service notice hidden). Granting it here restores both -- the update notification
# shows, and the min-importance service notice reappears in the shade. The in-app "Install
# pushed update" button works regardless, so this grant is a convenience, not a requirement.
log "granting POST_NOTIFICATIONS (for pushed-update alerts)"
adb -s "$SERIAL" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS || \
  err "could not grant POST_NOTIFICATIONS; use the in-app Install button instead"

# Doze would otherwise drop inbound packets to the listener once the phone has been
# idle for a while -- the exact state the app exists to recover from. Doing it here
# over adb means the user never has to find the toggle by hand.
log "exempting from battery optimisation"
adb -s "$SERIAL" shell dumpsys deviceidle whitelist "+$APP_ID" >/dev/null

# TURN_SCREEN_ON is an appop, not a runtime permission: it has no prompt and only this
# command grants it. Without it a ping cannot light a dark screen.
log "granting TURN_SCREEN_ON (wake the screen on adb activity)"
adb -s "$SERIAL" shell appops set "$APP_ID" TURN_SCREEN_ON allow

# A 512 KiB crash buffer scrolls in minutes, and a crash scan after the fact then finds nothing. Samsung caps every buffer at 5 MiB and the shell cannot set
# the persistent property, so this lasts until the next reboot; rerun bootstrap after one.
log "raising logcat buffers to 5 MiB (until reboot)"
adb -s "$SERIAL" shell logcat -G 5M >/dev/null || err "could not resize the logcat buffers"

log "starting the app"
adb -s "$SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null

# Give the app a moment to start, generate its token and write token.txt before
# run-as tries to read it.
sleep 2

log "pulling auth token"
mkdir -p "$CONFIG_DIR"
token="$(adb -s "$SERIAL" shell run-as "$APP_ID" cat files/token.txt | tr -d '\r\n')"
[ -n "$token" ] || { err "could not read token.txt via run-as -- is the app running?"; exit 1; }
printf '%s' "$token" > "$TOKEN_FILE"
chmod 600 "$TOKEN_FILE"

log "resolving phone IP"
ip="$(adb -s "$SERIAL" shell ip route get 1.1.1.1 2>/dev/null \
  | awk '{for (i=1;i<=NF;i++) if ($i=="src") print $(i+1)}')"
[ -n "$ip" ] || { err "could not determine the phone's LAN IP"; exit 1; }

printf 'IP=%s\n' "$ip" > "$CONFIG_FILE"
chmod 600 "$CONFIG_FILE"

log "done: $CONFIG_FILE -> IP=$ip, token stashed in $TOKEN_FILE"
log "manual, once: Settings > Battery > Background usage limits > Never sleeping apps > add ADB Connection Service"
log "test with: tools/adbwake  (or the installed ~/.local/bin/adbwake)"
log "push a new build with adb unreachable: adbpush app/build/outputs/apk/debug/app-debug.apk"
