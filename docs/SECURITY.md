# Security

The listener is one TCP port on the LAN with three endpoints, all requiring a 128-bit
`SecureRandom` token generated on the device and readable only by the app.

The reason the blast radius is small is that **enabling wireless debugging is not the same
as authorising anyone to use it**. An adb peer still has to pair first — a separate port and
a six-digit code shown on the phone's screen — so a stolen token buys an attacker the
ability to turn on a setting you can see and switch off, plus the device model and port
number. It does not buy a connection. And adb access is not root: `adbd` runs as `shell` on
production builds, which cannot read other apps' data or touch the system partition.

Deliberate choices, not accidents:

- **`/enable` is not a generic settings proxy.** The written key and values are hardcoded;
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
- **`AwakeReceiver` is exported but gated on `DUMP`**, which `shell` holds and no third-party app
  can, so only adb can ping it. Like `/enable`, it writes one hardcoded setting.
- **The service is `exported="false"`** — unreachable through Android IPC. The socket is the
  only way in. `BootReceiver` is exported because it must be, and `BOOT_COMPLETED` is a
  protected broadcast only the system can send.
- **`allowBackup="false"`**, so the token cannot be lifted out with `adb backup`.
- **No request can crash the listener.** It is the only thing reachable when the phone is
  otherwise unreachable, so an uncaught throw on a connection thread — which on Android kills
  the whole process — must never be possible.

Known and accepted, on a home LAN:

- **Plain HTTP, token in the query string.** Anyone able to sniff or MITM your LAN can read
  it. Given that the token alone cannot produce a connection, encrypting it is not worth the
  key management on a personal network.
- **The socket binds all interfaces**, so the port is exposed on any network the phone joins,
  including hostile ones. Worth revisiting if this phone spends time on public Wi-Fi; binding
  to the Wi-Fi interface or refusing off-subnet callers would close it.
- **The token never rotates.** Clearing app data is the only way to change it.
