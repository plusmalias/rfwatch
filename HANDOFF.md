# HANDOFF — shared log between Claude and Codex

Git (`github.com/plusmalias/rfwatch`, branch `main`) is the link between the two agents
working on RFWatch. Whoever works on the app next reads this file first and updates it last.

## Protocol

1. `git pull` before starting. Never force-push; never rewrite `main`.
2. Build + test before committing:
   `./gradlew :core:test :app:testMapsOnlineDebugUnitTest assembleMapsOnlineDebug assembleOfflineDebug`
3. On the phone, install **mapsOnline** (`installMapsOnlineDebug` / `adb install -r`), never
   uninstall: the encrypted DB holds the user's collected data. Any schema change needs an
   explicit Room `Migration` (the builder still has `fallbackToDestructiveMigration()`).
4. Keep the **offline flavor network-free** (no INTERNET; `app/src/offline/AndroidManifest.xml`
   strips MapLibre's). Online-only assets go in `app/src/mapsOnline/`.
5. Commit author: `Jorden Armstrong <199205755+plusmalias@users.noreply.github.com>`, no
   agent/co-author trailers. No secrets, home paths or real coordinates in code/tests.
6. Finish by appending a dated entry below: what changed, what was verified (tests, on-device),
   what is in progress / next, and anything the other agent must not undo.

## Log

### 2026-10-31 — Claude

**Map (mapsOnline) markers were invisible — fixed, verified on the ZTE P660:**
- The radio GeoJSON effect was keyed on `mapped` with a `delay` inside; devices/contacts
  re-emit several times a second, so it was cancelled every time (577 starts, 0 updates).
  Now a conflated `snapshotFlow` (first update immediate, then ≤1/s).
- Symbol (text) layers shared the GeoJSON sources with the circle layers while the style had
  no `glyphs` URL, so MapLibre never finished those sources — circles hidden too. Glyphs
  (Noto Sans Bold, ASCII, OFL) are now bundled in `app/src/mapsOnline/assets/glyphs` and
  labels use their own sources (`*_LABEL_SOURCE`) so fonts can never hide markers.
- Temporary map diagnostics removed (MapScreen/RfWatchApp); OSM User-Agent kept.

**Ingest performance / correctness:**
- `RfWatchRepository`: scanning runs on one background dispatcher; write-behind device cache,
  batched transaction flush every 3 s, ≤1 stored observation per device per 2 s, radar
  contacts published ≤1/s. Sweep capture and room sweep still see every advert.
- `startMonitoring` is idempotent (repeat service starts no longer stack collectors).
- `MonitorService`: a refused location-FGS start (Android 14 background restart) now stops
  the service instead of crashing.
- `LocationProvider` passes the main Looper (needed now that collection is off-main).
- `RuleEngine` precompiles SSID regexes (was compiling per rule per advert).
- Settings → Wipe all data now goes through `repo.wipeAllData()` (`clearAllTables` + caches);
  deleting the file under an open Room connection did not actually wipe.
- New `repo.setDeviceFlags(id, own, trusted)` — use it (not the DAO) so the write-behind
  cache can't overwrite the flags.

**Verified:** core 58/58, app 3/3 (both flavors); both APKs build; offline APK has no
INTERNET/network-state permission and no glyphs; on-device: no crashes, data preserved.

**In progress (Claude, next):**
1. `core/src/main/resources/vendors/` (Bluetooth SIG company IDs, IEEE OUI — added, not yet
   wired): vendor lookup in `:core` + tests. OUI only for Wi-Fi BSSIDs / public BLE addresses
   (BLE address type via API 34 `BluetoothDevice.getAddressType()`); random addresses are
   labelled "Private / rotating address", not "Brand unknown".
2. BLE payload decoders (Apple Continuity, Microsoft CDP, Google Fast Pair, iBeacon/Eddystone,
   Appearance, TX power) → device kinds; **Devices tab gets useful category filters** like the
   existing "Axon / law" one (phones, computers, audio, wearables, TV/media, vehicles/fleet,
   beacons, hotspots, cameras/IoT, smart home, mine/trusted, new).
3. DB v3→v4 migration: per-device stats (seen count, RSSI min/max), kind/model, Wi-Fi
   security/channel/band.
4. Device actions (mark mine / trust / locate / sweep), follower detection wiring
   (`FollowerScorer` is unused by the app), retention purge (never called), CSV export,
   compact device rows.

Codex: if you pick something from this list, note it here first so we don't collide.

### 2026-10-31 (later) — Claude

- **Sweep fixed for phones without a compass.** The ZTE P660 has no magnetometer, gyroscope
  or rotation-vector sensor (accelerometer only), so `HeadingProvider` never emitted a heading
  and every sweep captured 0 samples (silently showing nothing). Added a **guided step sweep**
  (8 × 45°, vibration cue every 5 s, bearing relative to the start direction), live sample
  counter, explicit "not enough data" readout, BLE-first target list. `HeadingProvider.hasCompass`.
- **Map legend now truthful:** counts of located / moving / heard-only, and why. Green/amber
  only appear after ≥8 GPS-tagged hearings spread ≥100 m (drive/walk past) — grey when stationary
  is expected, not a bug. Tap card shows the estimator's reason.
- `AdvParser` now keeps GAP Appearance (0x19) and TX power (0x0A) on `BleAdvertisement`.
- **In progress (Claude): verbose device database** — `core/.../id/` (DeviceKind, VendorDb,
  BleFingerprinter, WifiFingerprinter, NameHints) + data resources from the Bluetooth SIG
  registries (16-bit service/member UUIDs, full appearance subcategories, latest company IDs),
  Fast Pair / Apple / Samsung model tables, large name-pattern DB → Devices-tab category filters.
  Codex: please don't edit `core/.../id/`, `DevicesScreen.kt` or `DeviceIdentity.kt` until that lands.

### 2026-10-31 (evening) — Claude — phone battery died; NOT yet verified on device

Pushed, compiled + unit-tested (core 64/64, app 3/3), **not installed/tested on the phone yet**:
- **Hunt mode (Sweep tab, default):** hot/cold device finding — every advert from the target
  (`repo.setHuntTarget` / `vm.hunt`), EWMA-smoothed dBm, HOTTER/COLDER vs 4 s ago, signal-lost
  notice, Geiger beeps via `HuntTone` + `ToneGenerator`. Works without a compass.
- **Sweep plot:** wedges per sector (strongest highlighted), arrow only when the fit is
  confident, "strongest step X° right of start", warning when the signal barely changed.
- **Device database (data in place, partly wired):** `core/src/main/resources/devices/` —
  `uuid16.tsv` (801 SIG service + member UUIDs), `appearance.tsv` (342 category/subcategory),
  `fastpair.tsv` (500 Google Fast Pair models), `apple_pp.tsv` (22), `apple_actions.tsv` (15),
  `samsung.tsv` (Galaxy Buds 24-bit / Watch 8-bit EasySetup IDs: buds model = mfr bytes
  10,11,13 after prefix `42 09 81 02 14 15 03 21 01 09`; watch = byte 11 after
  `00 01 00 02 00 01 01 FF 00 00 43`); `vendors/company_ids.txt` refreshed (4060, SIG YAML).
  Code: `core/.../id/` (DeviceKind, VendorDb, BleFingerprinter, WifiFingerprinter, NameHints)
  + `IdentificationTest`. BleFingerprinter still uses its built-in Apple table; loading the
  TSVs (DeviceDb) and wiring kinds into the repository/Devices tab is the next step.

**User's open asks (priority order):**
1. **Radar shows "random spots"** — `RadarScreen.drawRadar` puts every contact without a LOCATED
   estimate at an angle from `id.hashCode()`. On a compass-less phone that is every contact.
   Plan: proximity scope — angle = labelled category sector, radius = RSSI proxy; real bearings
   only for drive-by LOCATED estimates.
2. **Police / law-enforcement detection with live notifications** (focus): expand
   `rules/law_enforcement.json` (Axon/TASER, body-cam and in-car video, police vehicle
   routers/modems, ALPR) and make detections raise an immediate high-priority notification
   (+ optional TTS), with per-device debounce, event-log entry and a "law" filter. Heuristic only —
   say so in the UI.
3. Wire the device database → `DeviceEntity.kind/model` (Room v3→v4 migration, keep data) →
   Devices-tab category chips with counts (Phones, Computers, Audio, Wearables, TV/media,
   Vehicles, Smart home, Health, Beacons, Hotspots, Routers, Trackers, Axon/law, Cameras).
4. Then: device actions (mine/trust/locate), follower wiring, retention purge, CSV export.
