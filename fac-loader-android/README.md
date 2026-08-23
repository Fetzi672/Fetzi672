# FAC Loader V12

Standalone Android loader for the untouched original package `com.cocfz.com.freescript`.

## Purpose

V12 deliberately does **not** patch, re-sign, inject into, or otherwise change the original Aiwan/CoC APK. The original package keeps its original signing identity, `classes.dex`, `script.lr`, resources and NX/Lua runtime.

## Flow

1. Open FAC Loader.
2. Loader requires working root (`su`).
3. FAC verifies the license against `https://fac.fetzi-ai.de/api/android/licenses/verify` using appVersion `310.0` and the stable FAC V6 device identity.
4. The license key is stored encrypted with Android Keystore AES-GCM.
5. Loader force-stops any old `com.cocfz.com.freescript` process, starts its foreground license guard, then launches the original package through Android's own package launcher intent.
6. Guard checks local expiry every 30 seconds and re-verifies with the server every 5 minutes.
7. Invalid/expired/revoked/unreachable guard state is fail-closed and root force-stops the original package.
8. Swiping/removing the FAC Loader task ends the current FAC session and closes the original package.

## V12 scope

This is intentionally the first minimal external-loader build. It is designed to prove that the original second NX UI continues to render when the original APK is completely untouched.

Direct-launch hardening is intentionally not part of this first test build: when FAC Loader/Guard is not active, Android can still launch the original icon normally. That can be hardened after the external-loader runtime path is confirmed stable.

## Install/test

- Keep/install the known-good original APK unchanged.
- Install `FAC_Loader_v12.apk` separately.
- Open FAC Loader and allow root when requested.
- Enter the FAC license key on first launch.
- After successful verification the original app launches automatically.
- Test the original first UI -> Confirm -> second dynamic UI flow.

The loader itself uses a per-build test signing key generated only inside GitHub Actions. It does not reuse or alter the original APK signing key.

Build: GitHub Actions `FAC Loader V12 APK Build`.
