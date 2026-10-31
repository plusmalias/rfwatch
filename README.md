# RFWatch

An Android RF situational-awareness and counter-surveillance tool: it listens (passively)
to the BLE and Wi-Fi around you, estimates **where** signals are coming from, flags item
trackers and surveillance gear, and warns you if something appears to be **following** you.

Built for **your own devices, premises and network** — authorised security/privacy work.
**Designed for Australia** (metric units, local magnetic declination, AU agency/fleet
vendor rules, AU English TTS voice).

---

## Honest physics — read this first

A phone has **omnidirectional antennas and no angle-of-arrival array**, so it **cannot
measure a true bearing in a single instant**. Every direction in RFWatch is an *estimate*
from one of three methods, and **every estimate carries a confidence value**. The UI never
shows a bearing without one, and never dresses up a hash-angle dot as a real bearing.

| Method | How | What you get |
|---|---|---|
| **Heading sweep** (stationary) | You turn 360° with the phone to your chest; your body shadows the signal | A bearing from a cosine-lobe fit, with R²/depth confidence |
| **Drive-by** (moving) | GPS-tagged RSSI samples fit a path-loss model | A position + 2σ error ellipse, or "MOBILE → trend" |
| **Wi-Fi RTT** (supported APs only) | 802.11mc round-trip time | A true **distance** (not a bearing), used as a hard constraint |

If a fit is too weak, RFWatch says **"no clear direction"** rather than guessing.

---

## Architecture

Two Gradle modules:

- **`:core`** — pure Kotlin/JVM, **no Android dependencies**. All the RF maths, parsers,
  scoring and rules live here so they can be unit-tested on a bare JDK (no device, no
  emulator). This is where the correctness-critical code is, and it is covered by tests.
- **`:app`** — the Android app (Kotlin + Jetpack Compose). Sensors, BLE/Wi-Fi/RTT scanning,
  the encrypted database, alerts and the UI. It wraps `:core`; it does not re-implement the
  maths.

### Build-step → where it lives

| Spec step | `:core` (tested) | `:app` (Android) |
|---|---|---|
| 1 HeadingProvider | `df/HeadingFusion` | `sensors/HeadingProvider` (TYPE_ROTATION_VECTOR + `GeomagneticField`) |
| 2 Sweep / Hunt | `df/SweepFit`, `df/HuntTone` | `ui/screens/SweepScreen` (polar plot) |
| 3 Drive-by | `df/PathLossLocalizer` (LM), `df/DriveByEstimator`, `df/Mobility` | wired via `repo/RfWatchRepository` |
| 4 Wi-Fi RTT | distance constraints in `PathLossLocalizer` | `scan/RttRanger` (gated on `FEATURE_WIFI_RTT`) |
| 5 Radar v2 | — | `ui/screens/RadarScreen` (heading-up, wedges, grey ring) |
| 6 Trackers | `cs/TrackerParser`, `cs/AdvParser`, `cs/TrackerIdentityLinker` | BLE pipeline in repository |
| 7 Follower | `cs/FollowerScorer`, `cs/OwnDeviceLearner` | — |
| 8 Room sweep | `cs/RoomSweep` + `rules/*` | `ui/screens/RoomSweepScreen` |
| 9 Baselines | `cs/Baseline` | geofence plumbing in DB |
| 10 Map / timeline | `geo/*` projections | `ui/screens/MapScreen`, `EventLogScreen` |
| 11 Encryption / retention / alerts | `data/RetentionPolicy`, `alerts/AlertPhrasing` | SQLCipher + Keystore, `alerts/AlertManager` |

---

## Tracker identifiers (verified 2026-10-31)

Classified by **payload, not MAC** (these all rotate their MAC). Identifiers checked
against the **Bluetooth SIG 16-bit UUID member registry**, IEEE OUI registry and vendor /
reverse-engineering references; sources are cited inline in `core/.../cs/TrackerParser.kt`
and the JSON rule packs.

- Apple Find My — company `0x004C`, offline-finding type `0x12`; accessory service `0xFD44`
- Samsung SmartTag — `0xFD5A` (registered) / `0xFD59` (unregistered)
- Tile — `0xFEED`, `0xFEEC`, `0xFD84`
- Chipolo — `0xFE33`, `0xFE65`
- Google Find My Device (FMDN) — service data `0xFEAA`, FMDN frame `0x40`/`0x41`
  (disambiguated from legacy Eddystone by the frame byte)
- IETF **DULT** is the umbrella spec these conform to (no single UUID)

Where a format is not publicly documented (e.g. Apple's status/battery bits) the code says
so and treats it as best-effort.

---

## Cellular / IMSI-catcher ("stingray") detection

Watches the serving + neighbour cells via `TelephonyManager`/`CellInfo` and flags
behaviour consistent with a cell-site simulator. **Heuristic, non-rooted** — stated plainly
in the UI:

- A non-rooted phone **cannot read the baseband or layer-3 signalling**, so (unlike
  SnoopSnitch, which needs root + a Qualcomm chipset) RFWatch cannot see silent SMS,
  cipher/auth downgrades or paging. It reasons only from cell identity, radio type,
  neighbour set and signal.
- Heuristics: **2G downgrade** after seeing LTE/NR, a **lone cell** with no neighbours,
  an **unknown cell** (after a learning window), **LAC/TAC inconsistency** for a known
  cell id, **rapid reselection**, and an **implausibly strong signal**. Weights combine
  into LOW/MEDIUM/HIGH.
- False positives happen (roaming, a new tower, a genuine 2G hole, a femtocell) and a
  careful catcher can evade. **HIGH means "worth checking", not proof.** The logic is in
  `core/.../cs/cell/StingrayDetector.kt` and is unit-tested.

## Prior art this draws on

- **AirGuard** (TU Darmstadt) — BLE AirTag/SmartTag/Find My + "seen in ≥3 locations"
  follower detection → covered by the tracker + follower modules.
- **SnoopSnitch / AIMSICD / CellGuard / Crocodile Hunter** — IMSI-catcher detection →
  the cellular module above (heuristic, since we're non-rooted).
- **WiGLE** — wardrive logging/mapping → GPS-tagged observations + the session map.

## Privacy & data protection

- **No INTERNET permission in the default (`offline`) flavor.** A separate `mapsOnline`
  flavor adds INTERNET purely for OSM tiles and is never the default.
- Database **encrypted at rest with SQLCipher**; the key is a random passphrase wrapped by
  an **Android Keystore** AES-GCM key (hardware-backed where available).
- **Retention** (default 30 days) with automatic purge; **Wipe-all-data** button.
- Backups/device-transfer excluded (`data_extraction_rules.xml`).
- Records bystanders' devices — handle accordingly. In Australia, surveillance-device and
  privacy laws vary by state; this tool is for your own property/devices/network.

---

## Build & verify

Requires a JDK 17 and the Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`).

```bash
# Run the maths/logic unit tests (JDK only, no Android SDK needed):
./gradlew :core:test

# Full build + all unit tests (needs Android SDK):
./gradlew assembleOfflineDebug test

# Install the default offline build:
./gradlew :app:installOfflineDebug
```

### Definition of done (your spec) — on-device checks
- a sweep on your own BLE device gives a bearing that points the right way
- a drive past your own router produces an ellipse that contains it
- carrying a second phone/tag in the car 15+ min across 3 locations triggers "Possible
  follower"

---

## Verification status (be aware)

- **`:core`** — complete and **verified: `./gradlew :core:test` → BUILD SUCCESSFUL, 39/39
  passing** (cosine sweep fit, LM localiser <50 m + error ellipse + RTT, mobility/trend,
  tracker hex-fixture parsers + identity linking, follower scoring incl. exclusions,
  own-device learning, baselines, room sweep, ENU/geo, heading fusion, rule packs,
  retention purge, alert phrasing, and the IMSI-catcher/stingray heuristics).
- **`:app`** — complete and wired, but it was authored on a machine **without a JDK or
  Android SDK** (toolchain downloads were network-throttled), so it has **not yet been
  compiled here**. Run `./gradlew assembleOfflineDebug test` on a toolchain'd machine; expect
  to fix a small number of compile nits that a first build surfaces.
- **Map base layer** is the one deliberate stub: the session **overlays** (located emitters,
  2σ ellipses) render offline via Compose canvas; the MapLibre **MBTiles base layer** is a
  documented drop-in (dependency already declared).

---

## Offline map tiles (AU)

The default build renders no network tiles. For the base layer, import an MBTiles file —
e.g. an Australia extract generated with `tilemaker`/`planetiler` from OpenStreetMap, or a
state extract — and point the map at it. Use the `mapsOnline` flavor only if you want live
OSM tiles.
