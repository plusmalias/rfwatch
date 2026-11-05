<div align="center">

# 📡 RFWatch

### See the radio world around you. Know if something is watching you.

**An Android counter-surveillance and RF situational-awareness app.**
RFWatch passively listens to the Bluetooth, Wi-Fi and cellular signals around you,
works out roughly **where** they're coming from, flags **item trackers, hidden cameras,
stalkerware and surveillance gear**, and warns you when something seems to be
**following you**.

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Version](https://img.shields.io/badge/version-0.3.0-blue)
![Offline](https://img.shields.io/badge/default%20build-no%20internet%20permission-success)
![Encrypted](https://img.shields.io/badge/data-SQLCipher%20encrypted-informational)
[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

[**⬇️ Download the APK**](../../releases/latest) · [Features](#-features) · [Install](#-install) · [Privacy](#-privacy--security) · [How it works](#-how-it-works-honestly) · [Build](#-build-from-source)

</div>

---

## Why RFWatch?

Cheap trackers, hidden cameras and phone spyware have made covert surveillance easy.
For people in abusive relationships, being stalked, or simply wanting to know what's
around them, *"is someone tracking me?"* can be a serious safety question.

RFWatch answers it with the radios already in your phone. There's no extra hardware,
no account and no cloud. It's built to be **honest about what a phone can and can't
detect**: every result carries a confidence level, and when the evidence is weak it
says so rather than guessing.

> **Designed in Australia.** Metric units, local magnetic declination, Australian
> emergency-service and fleet vendor rules, and Australian support services built in.

---

## ✨ Features

### 🎯 Trackers & followers
- **Item-tracker detection** by payload, not MAC address, so it keeps working as trackers rotate their addresses:
  - Apple **AirTag / Find My**
  - Samsung **SmartTag**
  - **Tile**
  - **Chipolo**
  - **Google Find My Device** (FMDN)
- **Follower detection**: flags a device that keeps turning up with you across different places and times.
- **Approach / recede trends**: arrows show whether a signal is getting closer or moving away.
- **"May be the same device" hints**: links Bluetooth address rotations and multi-radio routers so one device doesn't look like many.
- **Home watch**: notices new devices that appear and persist around home.
- **GPS-tracker** and **drone (Remote ID)** recognition.

### 🛡️ Protect hub: on-demand checks
| Check | What it does |
|---|---|
| **Room sweep** | Guided sweep of a room for cameras, IoT gear and unknown transmitters |
| **Car sweep** | Checks a vehicle for hidden trackers |
| **Camera finder** | Two modes: an **IR finder** (front camera sees night-vision LEDs) and a **lens finder** (torch + back camera catch the glint of a hidden lens) |
| **Stalkerware check** | Scans installed apps against public stalkerware/spyware indicators (package names, signing certificates, APK hashes) and flags apps holding risky permissions |
| **Skimmer check** | Looks for Bluetooth signatures common to card skimmers |
| **Ultrasonic beacon check** | Listens briefly (17–22 kHz) for inaudible tracking beacons |
| **Cell / IMSI-catcher check** | Heuristic "stingray" detection from cell identity and behaviour |
| **Location scope** | North-up plot of where nearby devices most likely are. Only devices with a real position estimate are drawn, never invented angles |

### 🚓 Awareness alerts
- **Police, ambulance and fire** recognition, including Axon body-cam / TASER signatures and fleet Wi-Fi. These are the alerts that come through loud and clear.
- **Evil-twin Wi-Fi** (a fake copy of a network you trust) and common **wireless attack-tool** signatures.
- **Hidden cameras and IoT** devices by vendor and broadcast fingerprint.

### 🧭 Find it
- **Radar**: live, heading-up radar with scan-health readout, distance rings, device icons, trend arrows and a "nearby now" list.
- **Hunt mode**: a hotter/colder tone guides you to a device.
- **Heading sweep**: turn slowly on the spot and RFWatch estimates the direction.
- **Drive-by locate**: GPS-tagged readings estimate a fixed transmitter's position, with an error ellipse.
- **Map and history**: where each signal was heard over time (MapLibre + OpenStreetMap in the maps build).

### 🗂️ Organised and easy to use
- **Devices list** with filters (time window, threats, new, named, hide mine), multi-kind selection, sorting and nicknames.
- One-tap cross-links from any device to **Details → Locate → Hunt → Map**.
- **Quick Settings tile** and **home-screen widget**.

### 🤫 Discreet mode: built for personal safety
- **Disguise**: the app can appear as a working **Calculator**. Type your PIN into the calculator to open RFWatch.
- **Neutral notifications** that don't say what the app is.
- **Shake to hide**.
- **App lock** with a PIN or biometrics.
- **Evidence export**: an HTML report plus CSV/JSON, with sightings, alerts, a timeline and map links, ready to hand to police, a lawyer or a support worker.

---

## 📲 Install

1. Go to [**Releases**](../../releases/latest) and download an APK:
   - **`RFWatch-0.3.0-maps.apk`**: recommended. Live street maps; uses the internet **only** for map tiles.
   - **`RFWatch-0.3.0-offline.apk`**: maximum privacy. The app has **no internet permission at all**.
2. Open the downloaded file on your phone. If Android says it can't install unknown apps, tap **Settings**, allow **"Install from this source"**, then go back and tap **Install**.
3. Open RFWatch and grant **Location**, **Nearby devices / Bluetooth** and **Notifications**. It can't hear anything without them.
4. Keep **Bluetooth and Location switched on** while scanning.

**Requirements:** Android 8.0 (API 26) or newer. Tested on Android 14.

> These are early **test builds** (debug-signed), not Play Store releases. Expect rough edges, and please report what you find.

---

## 🔒 Privacy & security

- **Your data never leaves your phone.** RFWatch has no accounts, no analytics and no cloud.
- **The offline build has no `INTERNET` permission**, so it physically cannot send data anywhere.
- The maps build only requests the OpenStreetMap tiles for the area on screen. Scan records stay on the device.
- **Encrypted at rest** with SQLCipher. The key is wrapped by the Android Keystore (hardware-backed where available).
- **Automatic retention purge** (default 30 days) and a **wipe-all-data** button.
- Excluded from cloud backup and device transfer.

---

## 🔬 How it works, honestly

A phone has **omnidirectional antennas and no direction-finding array**, so it **can't measure a true bearing in an instant**. Every direction in RFWatch is an *estimate*, and every estimate carries a confidence value.

| Method | How | What you get |
|---|---|---|
| **Heading sweep** (standing still) | Turn 360° with the phone against your chest; your body shadows the signal | A bearing from a cosine-lobe fit, with a confidence score |
| **Drive-by** (moving) | GPS-tagged signal readings fit a path-loss model | A position with an error ellipse, or "mobile, trending" |
| **Wi-Fi RTT** (supported access points) | 802.11mc round-trip timing | A real **distance** used as a hard constraint |

If a fit is too weak, RFWatch says **"no clear direction"** instead of drawing a fake dot.
Poor GPS fixes are ignored for direction, and gaps in scanning aren't mistaken for a device leaving.

**Cell / IMSI-catcher detection is heuristic.** A normal (non-rooted) phone can't read baseband signalling, so RFWatch reasons from cell identity, radio type, neighbour cells and signal strength:
- a 2G downgrade
- a lone cell
- an unknown cell
- a LAC/TAC mismatch
- rapid reselection
- implausibly strong signal

A **HIGH** result means *worth checking*, not proof.

### Tracker identifiers
Classified by advertisement payload and checked against the Bluetooth SIG UUID registry, the IEEE OUI registry and published research:
- Apple Find My: `0x004C` type `0x12`; accessory `0xFD44`
- Samsung SmartTag: `0xFD5A` / `0xFD59`
- Tile: `0xFEED` / `0xFEEC` / `0xFD84`
- Chipolo: `0xFE33` / `0xFE65`
- Google FMDN: `0xFEAA` frames `0x40`/`0x41`

Undocumented fields are treated as best effort, and the code says so.

### Architecture
- **`:core`**: pure Kotlin/JVM with no Android dependencies. All the RF maths, parsers, scoring and detection rules live here and are covered by **130+ unit tests** that run on a bare JDK.
- **`:app`**: Android (Kotlin + Jetpack Compose). Scanning, sensors, the encrypted database, alerts and UI. It wraps `:core` and never re-implements the maths.

Detection rules ship as JSON rule packs (`trackers`, `law_enforcement`, `emergency_services`, `fleet`, `cameras_iot`, `attack_tools`), alongside vendor and stalkerware indicator databases.

### Standing on the shoulders of
**AirGuard** (TU Darmstadt) for tracker and follower detection; **SnoopSnitch, AIMSICD, CellGuard and Crocodile Hunter** for IMSI-catcher research; **WiGLE** for wardrive mapping; and the **Echap** / **MVT** stalkerware indicator projects.

---

## 🛠️ Build from source

You'll need **JDK 17** and the **Android SDK** (platform 34). See [BUILD.md](BUILD.md) for the full guide.

```bash
./gradlew :core:test                 # detection logic tests (JDK only)
./gradlew assembleOfflineDebug       # no-internet build
./gradlew assembleMapsOnlineDebug    # build with live maps
```

---

## 🆘 If you're in danger

RFWatch can help you **gather evidence**, but it is not a substitute for help from people.
If you think someone is tracking or monitoring you:
- **Export your evidence first.**
- Think carefully before removing a tracker or app, because the person may notice.
- Talk to a specialist service.

| Australia | |
|---|---|
| Emergency | **000** |
| 1800RESPECT (domestic, family & sexual violence) | **1800 737 732** |
| MensLine Australia | **1300 78 99 78** |
| DVConnect (QLD) | **1800 811 811** |

---

## ⚖️ Responsible use

RFWatch is for protecting **yourself, your own devices, your property and your network**.
It records the presence of nearby devices, so treat that data with care. Surveillance and
privacy laws vary by state and country, and you are responsible for using it lawfully.
Detection is probabilistic: no result is proof on its own, and the absence of an alert is not a guarantee
that you aren't being tracked.

---

## 📜 License

**Copyright © 2026 Jorden Armstrong.**

RFWatch is free software, licensed under the [GNU General Public License v3.0](LICENSE).
You can use it, study it, change it and share it. If you distribute a modified version,
you must release your source code under the same licence and keep this credit.

For commercial licensing or partnerships, please get in touch via [GitHub](https://github.com/plusmalias).

---

<div align="center">

Built by **Jorden Armstrong**.
Feedback, bug reports and field results are very welcome: open an [issue](../../issues).

</div>
