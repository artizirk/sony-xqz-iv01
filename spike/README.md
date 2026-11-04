# XQZ Enabler

A minimal Android app that **enables the Sony XQZ-IV01 Vlog Monitor** by sending
the vendor heartbeat, then lets the phone's built-in secondary-display mirroring
show the screen on it. No MediaProjection, Sony code, or audio routing.

Confirmed on a **Galaxy S23** (One UI, DeX auto-start off) and a **Sony Xperia 1 V**:
the monitor mirrors the camera app, scaled/letterboxed to 1280×720.

## Features

- Requests USB permission for the monitor's control device (`054c:0e48`).
- Claims its vendor interface and sends `show` every **400 ms** from a foreground
  service (the DP sink disappears after 1 s without it).
- **Auto-reconnects** when the monitor is replugged while the service is running.
- **Quick Settings tile**: add “XQZ monitor” for one-tap start/stop.
- Notification with a **Stop** action; 4-hour partial wake lock.
- Sends `stop` on teardown; logs `flip`/ACKs to logcat (`XQZEnabler`).

Not included: audio input, FLIP handling, MediaProjection, or DeX control.

## Install

`XQZEnabler.apk` is release-signed (version 1.0). If you already installed the
earlier 0.1 spike, **uninstall it first** — the signing key changed.

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
3. Open **XQZ Enabler**, tap **Start**, allow USB access.
4. Open the Samsung **Camera** app. The monitor should show a scaled/letterboxed
   mirror of the phone screen.
5. Optional: add the **XQZ monitor** Quick Settings tile for one-tap toggling.

If DeX starts instead of mirroring, switch to **Screen mirroring** from the
notification, or turn off DeX auto-start and reconnect. The Xperia 1 V has no
DeX step; it mirrors directly.

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
- **Monitor replugged but not enabled:** if Android dropped the USB permission,
  open XQZ Enabler and tap Start to grant it again.

## Build

Requires Android SDK platform 35 and build-tools 35.0.0 at `$ANDROID_HOME`
(default `~/Android/Sdk`):

```sh
./build.sh
```

The APK is written to `./XQZEnabler.apk`.

Signing: `release.keystore` is local and gitignored. The committed APK is signed
with it. Keep that file to sign future updates that install over this build; if
it is missing, `build.sh` generates a new key and the resulting APK will not
update over the committed one.

Source layout:

- `src/ee/wut/xqz/enabler/MainActivity.java` — UI, USB permission, start/stop.
- `src/ee/wut/xqz/enabler/HeartbeatService.java` — USB claim, 400 ms `show`,
  reconnect handling, notification/wake lock.
- `src/ee/wut/xqz/enabler/MonitorTileService.java` — Quick Settings tile.
