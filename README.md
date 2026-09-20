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
`Settings.Global.adb_wifi_enabled`. Three token-authenticated endpoints:

| | |
|---|---|
| `GET /status?token=…` | Is debugging on, which adb port, is the permission still granted |
| `GET /enable?token=…` | Turn debugging on, report the resulting adb port |
| `POST /install?token=…` | Accept an APK, install it as a same-signed update to itself (below) |

**`tools/aconnect`** — the command you actually run. A ladder: already-attached → saved
endpoints → mDNS → `adbwake` → a rotated-port scan of any remembered IP still reachable,
retried for 60s because a sleeping phone drops inbound packets while its radio surfaces only
periodically. The scan is the "known IP, rotated port" recovery for when mDNS is dark (its
usual state under WSL) and debugging is still on, so `adbwake` finds nothing to re-enable; it
needs `python3`. `aconnect serial` prints the one transport to pass to `adb -s`.

**`tools/adbwake`** — the rung that talks to the app. Locates the phone (every remembered IP
first, then a sweep of both this PC's subnet and every `/24` the phone has been seen on, for
port 47821), calls `/enable`, prints `ip:port`. Sweeping the phone's historical subnets, not
just the PC's, is what recovers a network that hands the phone and PC addresses on different
`/24`s but still routes between them — a mesh or a main+guest split.

**`tools/adbpush`** — the update channel for when adb itself cannot be revived. It finds the
app's listener the same way `adbwake` does (shared `tools/lib/locate.sh`), then `POST`s an APK to
`/install`. The phone ends up one tap from the new build — a notification, or an in-app button —
without ever needing a working adb connection. It cannot install silently; see below.

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

The other thing no LAN trick can fix is a network that puts the phone and PC on subnets that
genuinely do not route to each other — a guest/AP-isolated Wi-Fi, or the phone on cellular
while the PC is on Wi-Fi. Knowing the phone's IP does not help: the problem is reachability,
not discovery, and nothing on the phone is reachable inbound. The sweep tries every `/24` the
phone has lived on, so a *routable* split (mesh, main+guest) recovers unattended; a
*non-routable* one needs the two put on one subnet — the phone's hotspot, USB, or a Tailscale
tailnet — and `aconnect` now says so instead of claiming the phone's address is unknown.

## Security

The listener is one TCP port on the LAN with three endpoints, all requiring a 128-bit
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
- **`/install` only ever installs a same-signed update of the app itself.** Accepting a pushed
  executable is the one endpoint that could be a real escalation surface, and two things fence
  it. First, signer pinning: the app installs the APK only if its signing certificate matches
  the app's own — Android's installer enforces this for any update, and the app re-checks it
  before the bytes reach the installer, so a token-holder cannot push arbitrary malware without
  the signing key, only a genuine rebuild of *this* app. Second, the app **cannot install
  silently** — it holds `REQUEST_INSTALL_PACKAGES`, not the system-only install permission, so
  the system's install-confirm screen and the user's tap are unavoidable. The feature removes
  the "sideload it by hand from Downloads" step, not the confirmation. The upload is size-capped
  and streamed to disk, and like every other path cannot crash the listener.
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
