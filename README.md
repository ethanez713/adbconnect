# adbconnect

Wireless ADB that reattaches itself, on a phone that keeps switching it off.

Android randomises the wireless-debugging port every session and disables the feature
entirely on reboot and on Wi-Fi change. The usual recovery is mDNS discovery, which fails
in exactly that case: a phone with debugging off advertises nothing. So the recovery path
has to start from something on the phone that is *always* listening on an address the PC
can predict — which is what this repo is.

## Parts

**The app** (`app/`) — a foreground service holding a hand-rolled HTTP listener on a fixed
port, `47821`. Foreground so Android will not kill the process; battery-optimisation exempt
so Doze does not drop inbound packets to it. It holds `WRITE_SECURE_SETTINGS`, which apps
cannot request at runtime and can only be granted over a working adb connection — that
grant is the entire reason the app exists, because it is what allows writing
`Settings.Global.adb_wifi_enabled`. Two token-authenticated GET endpoints:

| | |
|---|---|
| `/status?token=…` | Is debugging on, which adb port, is the permission still granted |
| `/enable?token=…` | Turn debugging on, report the resulting adb port |

**`tools/aconnect`** — the command you actually run. A ladder: already-attached → saved
endpoints → mDNS → `adbwake`, retried for 60s because a sleeping phone drops inbound packets
while its radio surfaces only periodically. `aconnect serial` prints the one transport to
pass to `adb -s`.

**`tools/adbwake`** — the rung that talks to the app. Locates the phone (saved IP as a hint,
else sweeps the current subnet for port 47821), calls `/enable`, prints `ip:port`.

**`tools/bootstrap.sh`** — one-shot setup, run while adb is already connected. Installs the
APK, grants the permission, exempts the app from battery optimisation, pulls the auth token
off the device, and symlinks both tools into `~/.local/bin`.

## State

| File | Holds |
|---|---|
| `~/.config/android-device` | Recent endpoints, most-recent-first |
| `~/.config/adbconnect` | Phone IP — a hint only; `adbwake` rewrites it |
| `~/.config/adbconnect-token` | Auth token, `0600` |

## Setup

```bash
source ~/android-tooling/env.sh     # JDK 17 + Gradle + SDK adb (v37) on PATH
./gradlew assembleDebug
tools/bootstrap.sh                  # while adb is connected
```

Thereafter, `aconnect`. Allow it up to 90s — the rungs that fix things are the slow ones at
the end, and cutting it short kills it before they run.

## The one thing it cannot do

The first time debugging is enabled on a **new Wi-Fi network**, Android shows *"Allow
wireless debugging on this network?"* and the settings write does not return until someone
taps it. That gesture is the dialog's entire purpose; no permission this app can hold will
dismiss it. A new network costs one tap, always. What the app does instead is say so — it
answers `503` with `awaitingConsent: true` rather than going silent, and `adbwake` prints
the tap it is waiting for.

## Security

The listener is one TCP port on the LAN with two GET endpoints, both requiring a 128-bit
`SecureRandom` token generated on the device and readable only by the app.

The reason the blast radius is small is that **enabling wireless debugging is not the same
as authorising anyone to use it**. An adb peer still has to pair first — a separate port and
a six-digit code shown on the phone's screen — so a stolen token buys an attacker the
ability to turn on a setting you can see and switch off, plus the device model and port
number. It does not buy a connection. And adb access is not root: `adbd` runs as `shell` on
production builds, which cannot read other apps' data or touch the system partition.

Deliberate choices, not accidents:

- **`/enable` is not a generic settings proxy.** The written key and value are hardcoded;
  no request parameter influences either. A general "write any secure setting" endpoint is
  what would turn `WRITE_SECURE_SETTINGS` into a real escalation surface.
- **The service is `exported="false"`** — unreachable through Android IPC. The socket is the
  only way in. `BootReceiver` is exported because it must be, and `BOOT_COMPLETED` is a
  protected broadcast only the system can send.
- **`allowBackup="false"`**, so the token cannot be lifted out with `adb backup`.
- **No request can crash the listener.** It is the only thing reachable when the phone is
  otherwise unreachable, so an uncaught throw on a connection thread — which on Android kills
  the whole process — must never be possible. See `400ab2b`.

Known and accepted, on a home LAN:

- **Plain HTTP, token in the query string.** Anyone able to sniff or MITM your LAN can read
  it. Given that the token alone cannot produce a connection, encrypting it is not worth the
  key management on a personal network.
- **The socket binds all interfaces**, so the port is exposed on any network the phone joins,
  including hostile ones. Worth revisiting if this phone spends time on public Wi-Fi; binding
  to the Wi-Fi interface or refusing off-subnet callers would close it.
- **The token never rotates.** Clearing app data is the only way to change it.

## Incidents

`docs/incidents/` holds post-mortems for failures worth not repeating. They record what the
evidence actually showed, which is usually more useful than the fix.
