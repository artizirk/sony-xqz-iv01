# XQZ Enabler

A minimal Android app that **enables the Sony XQZ-IV01 Vlog Monitor** by sending
the vendor heartbeat, then lets the phone's built-in secondary-display mirroring
show the screen on it. No MediaProjection, Sony code, or audio routing.

Version 1.0 was confirmed on a **Galaxy S23** (One UI, DeX auto-start off) and a
**Sony Xperia 1 V**: the monitor mirrors the camera app, scaled/letterboxed to
1280×720. Version 1.1 changes the reconnect, retry, tile, and notification paths
and has not yet been re-tested on hardware.

## Features

- Requests USB permission for the monitor's control device (`054c:0e48`).
- Claims the vendor interface (`0xff/0x01/0x01`, bulk OUT `0x02` / IN `0x81`)
  and sends `show` every **400 ms** from a foreground service (the DP sink
  disappears after 1 s without it).
- **Reconnects after a replug** when the app is allowed to handle the monitor
  automatically. Android grants USB permission per attach; choose **“Always
  open XQZ Enabler”** the first time and the transparent attach handler starts
  the service again. Without that choice, tap Start to allow access again.
- Retries transient USB failures with capped backoff instead of stopping.
- **Quick Settings tile**: add “XQZ monitor” for one-tap start/stop while
  running, with a subtitle showing running/waiting/retrying. While waiting it
  retries or opens the app for USB access.
- Notification with a **Stop** action; 4-hour partial wake lock, renewed while
  the heartbeat is running.
- Sends `stop` on teardown; logs `flip`/ACKs to logcat (`XQZEnabler`).

Not included: audio input, FLIP handling, MediaProjection, or DeX control.

## Install

`XQZEnabler.apk` is release-signed (version 1.1). If you already installed the
earlier 0.1 spike, **uninstall it first** — the signing key changed. Version 1.1
uses the same key as 1.0, so it installs over 1.0.

```sh
adb install -r XQZEnabler.apk
```

Or transfer the APK to the phone. If Samsung Auto Blocker blocks it, allow
installs for the file manager or temporarily disable Auto Blocker.

## Use

1. On Samsung devices, turn off DeX auto-start:
   **Settings → Connected devices → Samsung DeX → “Auto start when HDMI is
   connected” = off.**
2. Turn the monitor's screen switch **ON**, then connect the XQZ-IV01 to the
   phone with its USB-C cable.
3. Open **XQZ Enabler**, tap **Start**, allow USB access, and tick **“Always
   open XQZ Enabler”** if offered. That is what makes a replug automatic.
4. Open the Samsung **Camera** app. The monitor should show a scaled/letterboxed
   mirror of the phone screen.
5. Optional: add the **XQZ monitor** Quick Settings tile for one-tap toggling
   while the service is running.

If DeX starts instead of mirroring, switch to **Screen mirroring** from the
notification, or turn off DeX auto-start and reconnect. The Xperia 1 V has no
DeX step; it mirrors directly.

The app asks for notification permission when you first tap Start. Allow it so
the notification's **Stop** action stays available. If notifications are
blocked, use Stop in the app or the Quick Settings tile.

## Reconnect behavior

Android's USB permission is valid only for the current attach. There are two
paths after a replug:

- **Automatic:** the app is the default handler for `054c:0e48` (chosen with
  “Always open XQZ Enabler”). The system launches the transparent
  `UsbAttachActivity`, grants access, and the service reconnects.
- **Manual:** no default handler. The system may still grant access for that
  attach if you pick the app in the USB chooser; otherwise the service shows
  “Waiting for USB access” and you open the app and tap Start.

If you stopped the service first, a replug does not start it again. If the
service was enabled (not stopped) but Android killed the process, the attach
handler starts it again. A locked screen may defer that activity; open the app
manually if the monitor stays dark.

While the service is enabled, it keeps running and retries with capped backoff,
so a brief USB error does not require a restart.

## Tested devices

| Device | Result |
|---|---|
| Samsung Galaxy S23 | Works; requires DeX auto-start off |
| Sony Xperia 1 V | Works; mirrors directly |

## Troubleshooting

- **Black camera preview only:** the camera app is using a secure window. The
  next step would be a MediaProjection mirror or a custom Camera2 preview.
- **No display:** check that `show` is running (app status / notification) and
  that the monitor's screen switch is on.
- **Monitor replugged but not enabled:** choose “Always open XQZ Enabler”, or
  open XQZ Enabler and tap Start to grant USB access again.
- **Notification Stop action missing:** notifications are blocked; stop the
  service from the app or Quick Settings tile.
- **Status says reconnecting:** the service is retrying a USB failure; check
  `adb logcat -s XQZEnabler` and the monitor connection.

## Build

Requires JDK 17+ and Android SDK platform 35 with build-tools 35.0.0 at
`$ANDROID_HOME` (default `~/Android/Sdk`). The script also needs `zip` and
`keytool` on `PATH`:

```sh
export XQZ_STORE_PASS='your-keystore-password'
./test.sh     # host-side unit tests (pure Java, no device)
./build.sh    # runs test.sh first, unless XQZ_SKIP_TESTS=1
```

`build.sh` also accepts the keystore password from `spike/.storepass`
(gitignored). It requires `release.keystore`; a missing key is an error rather
than a silent new signing identity. Set `XQZ_ALLOW_NEW_KEY=1` only when you
intend to create a new key.

The APK is written to `./XQZEnabler.apk`. `release.keystore` is local and
gitignored. The committed APK is signed with it. Keep that file to sign future
updates that install over this build.

Source layout:

- `src/ee/wut/xqz/enabler/MainActivity.java` — UI, USB permission, start/stop.
- `src/ee/wut/xqz/enabler/UsbAttachActivity.java` — transparent USB-attach
  handler for automatic reconnect.
- `src/ee/wut/xqz/enabler/HeartbeatService.java` — service lifecycle, USB
  claim, retry/backoff, notification/wake lock.
- `src/ee/wut/xqz/enabler/HeartbeatLoop.java` — device-independent heartbeat
  and reply-read loops.
- `src/ee/wut/xqz/enabler/MonitorProtocol.java` — VID/PID, interface,
  endpoints, RX decoding.
- `src/ee/wut/xqz/enabler/RetryBackoff.java` — capped exponential backoff.
- `src/ee/wut/xqz/enabler/RunState.java` — shared service state.
- `src/ee/wut/xqz/enabler/MonitorTileService.java` — Quick Settings tile.
- `test/` — host-side tests for the pure classes; run `./test.sh`.
