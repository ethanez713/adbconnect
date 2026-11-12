# adbconnect

[![CI](https://github.com/ethanez713/adbconnect/actions/workflows/ci.yml/badge.svg)](https://github.com/ethanez713/adbconnect/actions/workflows/ci.yml)

**Wireless ADB that reconnects by itself.** Android switches wireless debugging off on
every reboot and Wi-Fi change, and moves it to a new port each session, so a phone you
were connected to yesterday is gone today. `aconnect` gets it back with one command and
no trip to Developer options.

## How it works

The usual recovery is mDNS discovery, which fails in exactly the case that matters: a
phone with debugging off advertises nothing. So recovery starts from something on the
phone that is always listening at an address the PC can predict.

- **A small Android app** holds `WRITE_SECURE_SETTINGS` and listens on one fixed LAN port.
  Asked with its token, it turns wireless debugging back on and reports the new port.
- **`aconnect`** is the command you run. It tries the cheap things first (an existing
  connection, saved endpoints, mDNS), then asks the app, then scans for a rotated port,
  and keeps retrying for a minute because a sleeping phone only answers now and then.
- **`aconnect serial`** prints the one device to pass to `adb -s`, so scripts never pick
  the wrong phone when several are attached.

It also keeps the screen awake during a session, limits automatic re-enabling to Wi-Fi
networks you allow, and can push an updated build of itself when adb cannot be revived.

## Setup

JDK 17 and the Android SDK on a Linux or WSL host, with the phone connected over adb once
(USB or a paired wireless session):

```bash
./gradlew assembleDebug
tools/bootstrap.sh        # installs the app, grants the permission, links the tools
```

One step no adb command can do: Battery → Background usage limits → **Never sleeping
apps** → add ADB Connection Service. After that:

```bash
aconnect                              # allow up to 90 s; the rungs that fix things are the slow ones
adb -s "$(aconnect serial)" shell     # always target the serial it prints
```

## What it cannot do

- **Tap the consent prompt.** The first time debugging is enabled on a new Wi-Fi network,
  Android asks on the phone's screen. The tools say they are waiting for that tap rather
  than going silent.
- **Cross networks that do not route.** A guest network with client isolation, or the
  phone on cellular, needs the two on one subnet: a hotspot, USB or a tailnet.

## Security

The listener answers only to a 128-bit token generated on the phone. Turning debugging on
does not authorise anyone to use it: an adb peer still has to be paired. The endpoint
writes one hardcoded setting, and the self-update path accepts only a build signed with
the app's own key and still needs a tap. The token travels over plain HTTP, which is a
deliberate trade for a home LAN. The full reasoning is in
[docs/SECURITY.md](docs/SECURITY.md).

How the pieces fit, the files they keep and multi-phone setups are in
[docs/DETAILS.md](docs/DETAILS.md).

## License

[MIT](LICENSE). Copyright (c) 2026 Ethan Zhou.
