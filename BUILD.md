# Building RFWatch & getting it on your phone

RFWatch is an Android app, so it must be compiled into an APK on a machine with the
Android toolchain, then installed on the phone. There is no prebuilt APK in this repo.

## Prerequisites

- **JDK 17** (Temurin/Corretto/any).
- **Android SDK** with platform **android-34** and build-tools 34.x.
  Either install **Android Studio** (bundles both), or the command-line SDK tools.
- A `local.properties` at the repo root pointing at the SDK (Android Studio writes this
  for you; otherwise `sdk.dir=/path/to/Android/sdk`). This file is gitignored.

## Option A — Android Studio (easiest; builds + installs for you)

1. Install Android Studio and open this folder.
2. Let Gradle sync (it downloads dependencies — first sync needs decent internet).
3. On the phone: Settings → About phone → tap **Build number** 7× to unlock
   **Developer options**, then enable **USB debugging**.
4. Plug the phone in and accept the "allow USB debugging" prompt.
5. In the toolbar, set **Build Variant** to **offlineDebug**, pick your phone, press **Run ▶**.
   It compiles and installs to the phone.

## Option B — command line

```bash
# verify the maths/logic (JDK only, no Android SDK needed):
./gradlew :core:test

# build the default offline APK:
./gradlew assembleOfflineDebug
#   -> app/build/outputs/apk/offline/debug/app-offline-debug.apk

# install to a USB-connected phone (adb from platform-tools):
adb install -r app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

Or copy that `.apk` to the phone and tap it (enable "install unknown apps" for your file
manager / browser).

## Flavors

- **offline** (default) — **no INTERNET permission**. Maps use imported offline MBTiles.
- **mapsOnline** — adds INTERNET purely for live OSM tiles. Build with
  `assembleMapsOnlineDebug`. Not the default.

## On first launch

Grant the runtime permissions it asks for: Location (fine), Nearby Wi-Fi devices,
Bluetooth scan/connect, Phone (for cell/IMSI monitoring), Notifications. On Android 11+,
"Allow all the time" location is needed for background follower detection.

## Known gotchas

- **First compile:** `:app` was authored without a local toolchain, so the first
  `assembleOfflineDebug` may surface a few compile errors to fix — expected for a codebase
  this size written without a compiler. `:core` is reviewed and unit-tested.
- **Wi-Fi RTT:** feature-gated. Most budget phones (incl. the Optus X Tap 3) lack the
  hardware; the feature simply shows as unavailable.
- **IMSI-catcher detection is heuristic** (non-rooted). See README for the honest limits.
- **SQLCipher** pulls a native lib; make sure `assembleOfflineDebug` completes the NDK/so
  packaging step (no action needed — it's a normal AAR).
