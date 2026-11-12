# How adbconnect works

The pieces, the files they keep, and how several phones share one PC. The quick start is in
the [README](../README.md); the threat model is in [SECURITY.md](SECURITY.md).

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
| `GET /enable?token=…` | Turn debugging on (off-then-on if it reads on but adbd isn't advertising), report the resulting adb port |
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

**Keep-awake window** (`StayAwake.kt`) — a screen that sleeps mid-session locks the keyguard and
idles Wi-Fi, which is when adb stops answering. `aconnect serial` and a successful `aconnect`
ping the app, which lights a dark screen and holds Developer options' *Stay awake* on for a
sliding window (default 30 min, set in the app); every ping pushes the deadline out. Charging
only, because that setting is a no-op on battery. The app owns the setting while the feature is
on and clears it at expiry unless something else changed it first.

**Allowed networks** (`NetworkGate.kt`) — Android 17 resumes wireless debugging on every Wi-Fi
join while `adb_wifi_enabled` reads on, and on a network it has not approved that resume raises
the consent prompt. So the app owns that setting per network: on an allowed SSID it is on, on any
other Wi-Fi, or with no Wi-Fi, it is off. The list is edited in the app; the network it is first
seen on is allowed automatically. Only the automatic turn-on is gated — `/enable`, and so
`aconnect`, and the in-app button work on any network. Reading an SSID needs the location
permissions `bootstrap.sh` grants, plus Location switched on.

**`tools/bootstrap.sh`** — one-shot setup, run while adb is already connected. Installs the
APK, grants the permission, exempts the app from battery optimisation, pulls the auth token
off the device, and symlinks both tools into `~/.local/bin`.

## State

| File | Holds |
|---|---|
| `~/.config/android-device` | Recent endpoints, most-recent-first |
| `~/.config/adbconnect` | Phone IP — a hint only; `adbwake` rewrites it |
| `~/.config/adbconnect-token` | Auth token, `0600` |
| `~/.cache/aconnect/awake-*` | Per-transport stamp that throttles the keep-awake ping to one a minute |
| `~/.config/adbconnect-devices` | Phone registry: `<name> <ro.serialno> [project ...]`, first line is the default |

## Several phones

The registry ranks the phones attached to this PC. The first line is the default: the attach
ladder only counts a connection to it as success, and a bare `aconnect serial` returns it. A
phone with projects after its serial is reserved for those projects — a project is the repo's
directory name, or `$ACONNECT_PROJECT` — and `aconnect serial` never returns a reserved phone
to anyone else (`aconnect serial <name>` exits 3). A phone with no projects is open to all.
`aconnect devices` shows who may use what. Callers pass `"$(aconnect serial)"` to `adb -s`
rather than choosing a transport themselves; that is the only thing keeping a reserved phone
clean. Only the default phone is remembered in `~/.config/android-device`.

`aconnect unlock [name]` wakes a PIN-locked phone and enters its PIN from `$ACONNECT_PINS`
(default `~/.secrets/canonical/adbconnect/pins`, mode 0600, `<name> <pin>` per line), so the PIN
never appears in a caller's command line or output. It submits once and reports locked or
unlocked, because repeated wrong PINs start the lock screen's lockout timer.

## What it cannot do

The first time debugging is enabled on a **new Wi-Fi network**, Android shows *"Allow
wireless debugging on this network?"* and the settings write does not return until someone
taps it. That gesture is the dialog's entire purpose; no permission this app can hold will
dismiss it. A new network costs one tap, always — including the first time a newly allowed
network is joined. What the app does instead is say so — it
answers `503` with `awaitingConsent: true` rather than going silent, and `adbwake` prints
the tap it is waiting for.

The other thing no LAN trick can fix is a network that puts the phone and PC on subnets that
genuinely do not route to each other — a guest/AP-isolated Wi-Fi, or the phone on cellular
while the PC is on Wi-Fi. Knowing the phone's IP does not help: the problem is reachability,
not discovery, and nothing on the phone is reachable inbound. The sweep tries every `/24` the
phone has lived on, so a *routable* split (mesh, main+guest) recovers unattended; a
*non-routable* one needs the two put on one subnet — the phone's hotspot, USB, or a Tailscale
tailnet — and `aconnect` now says so instead of claiming the phone's address is unknown.
