# FAC Guard V14

FAC Guard V14 protects the original package `com.cocfz.com.freescript` without modifying, repacking, injecting into, or re-signing the original APK.

## Architecture

- Original Aiwan/CoC APK: 100% untouched.
- FAC Guard is a separate root-enabled Android app.
- Guard runs as a foreground service and starts again after boot.
- A root monitor checks the original process every ~0.5 seconds.
- Direct original launch without an active FAC session is immediately force-stopped and FAC Guard opens the license screen.
- With a saved key, an intercepted launch auto-verifies online and then relaunches the untouched original.
- Once a session is authorized, normal direct launches are allowed while the session remains valid.
- Local expiry check: every 30 seconds.
- Server recheck: every 5 minutes.
- Invalid / expired / revoked / server failure: fail-closed; original package is force-stopped.
- Root loss while an active session exists: fail-closed.

## License API

`POST https://fac.fetzi-ai.de/api/android/licenses/verify`

Request fields:

- `licenseKey`
- `deviceId` (FAC V6 stable device identity, 64 lowercase hex)
- `appVersion`: `310.0`

The license key is stored with Android Keystore AES-GCM.

## Setup

1. Keep/install the known-good original APK unchanged.
2. Install `FAC_Guard_v14.apk`.
3. Open **FAC Guard V14 once** and approve root access.
4. Enter the FAC license key and press `VERIFY & START ORIGINAL`.
5. From then on, normally use the **original Aiwan/CoC icon**.

When the original is started without an authorized FAC session, Guard blocks it, verifies FAC, then relaunches the original automatically.

## Guard persistence

- Android `BOOT_COMPLETED` receiver starts Guard after emulator/device boot when armed.
- A best-effort root keepalive is also installed under `/data/local/tmp` while Guard is armed. If the FAC Guard process disappears, the keepalive force-stops the protected original before restarting Guard.
- `DISARM FAC GUARD` stops the root keepalive and disables protection intentionally.

## Compatibility goal

V14 never changes the working original APK, so its original signing identity, manifest, `classes.dex`, `script.lr`, resources, launcher behavior, NX runtime, Lua runtime, and second dynamic UI render path remain exactly as supplied by the original APK.
