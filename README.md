# TailToggle

Pebble watch app plus Android companion for toggling Tailscale on the phone.

The watch app sends commands to PebbleKit JS. PebbleKit JS calls a local HTTP endpoint on the phone. The Android companion keeps that endpoint alive and sends Tailscale's Android broadcast intents:

- `com.tailscale.ipn.CONNECT_VPN`
- `com.tailscale.ipn.DISCONNECT_VPN`

Those intent actions are listed in Tailscale's changelog and are the same actions commonly used from Tasker-style automation. Current Android/Tailscale versions can still have reliability quirks around background VPN start, so the companion also has manual connect/disconnect buttons for testing.

References:

- Tailscale changelog: https://tailscale.com/changelog
- Android foreground service type requirements: https://developer.android.com/develop/background-work/services/fgs/service-types

## Build Pebble App

```sh
./verify-tailtoggle.sh
```

The installable PBW is written to:

```text
dist/tailtoggle.pbw
```

Install through the normal Pebble tooling:

```sh
pebble install --phone <phone-tailscale-ip> dist/tailtoggle.pbw
```

If the Pebble SDK install path times out but the Core Devices/Pebble phone app dev server is open on port `9000`, use the same direct WebSocket install method documented in `../t3pebble/AGENTS.md`, replacing `PBW_PATH` with `dist/tailtoggle.pbw`.

There is also a local helper for that path:

```sh
PEBBLE_PHONE=<phone-tailscale-ip> node scripts/install-core-devices.js
```

## Build Android Companion

On this machine, the companion has been verified with a local Android SDK under `android-sdk/`, Java 17, and Gradle 8.11.1. To rebuild:

```sh
./build-android-companion.sh
```

The installable debug APK is written to:

```text
dist/tailtoggle-companion-debug.apk
```

With Android Studio or another working Android Gradle setup, the equivalent manual commands are:

```sh
cd android-companion
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or install the copied artifact directly:

```sh
adb install -r dist/tailtoggle-companion-debug.apk
```

Open **TailToggle Companion** once after installing. It starts a foreground service listening on:

```text
http://127.0.0.1:17999
```

The Pebble app settings use that endpoint by default. You can set a shared token in the Android companion and in the Pebble app settings if you want one.

## Watch Controls

- `SELECT`: toggle based on whether Android reports an active VPN.
- `UP`: send Tailscale connect intent.
- `DOWN`: send Tailscale disconnect intent.

The status display is based on Android's public VPN transport state. Android does not expose a clean public API for confirming that the active VPN is specifically Tailscale, so the app reports whether any Android VPN is active plus whether the Tailscale package is installed.
