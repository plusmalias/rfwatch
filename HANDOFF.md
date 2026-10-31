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

### 2026-10-31 — Codex

**Field console UI refresh:**
- Introduced a consistent dark console palette, mint/amber/cyan signal colors, monospace
  technical labels, and a persistent RF//WATCH / PASSIVE RX header with matching bottom
  navigation.
- Restyled Radar, Devices, Sweep, Map, Cellular, Room Sweep, Event Log, More, and Settings
  screens with clearer section hierarchy, restrained borders, and coordinated panel colors.
- Settings now describes online map tile network use accurately; scanner behavior and stored
  data schema were not changed.

**Verified:** `:core:test`, `:app:testMapsOnlineDebugUnitTest`, `assembleMapsOnlineDebug`, and
`assembleOfflineDebug` passed with installed Gradle 8.7 (offline, cached dependencies). Installed
mapsOnline with `adb install -r` on the ZTE P660 at `192.168.0.172:5555`, preserving app data;
Radar screen rendered in the on-device screenshot. The phone reported 10–11% battery during
verification; no scan was started. Offline flavor was built as part of the task command.

**In progress / next:** continue Claude's device database wiring from the entries above. The
phone should be charged before longer live scan testing. Preserve this shared dark console theme
and keep location, distance, and detection claims appropriately qualified.

### 2026-10-31 (later) — Codex — Radar scroll / radio monitor follow-up

- Radar home is now one full-height lazy vertical list: scope, live BLE/Wi-Fi detections, then
  saved device inventory. This lets the entire page scroll while preserving the separate Devices
  tab for search and filters.
- `:core:test`, `:app:testMapsOnlineDebugUnitTest`, `assembleMapsOnlineDebug`, and
  `assembleOfflineDebug` passed with Gradle 8.7 and cached dependencies.
- On-device Activity Manager showed the foreground `MonitorService` active; it runs both BLE and
  Wi-Fi collectors. Radar displayed 16 live signals. Battery fell to 7% and the phone was not
  charging. The `adb install -r` attempt did not return a success status, and the screenshot still
  appeared to show the prior layout. **New UI on-device verification remains pending; charge the
  phone, reinstall mapsOnline, then scroll through Radar to confirm the saved inventory below.**

**Radar ideas for Claude after the identity work lands (keep claims evidence-based):**
1. Replace hash-jittered unknown-bearing spots with stable category lanes and concentric RSSI
   proxy bands; reserve compass bearings for `LOCATED` estimates only. Label the bands as rough
   signal strength, not measured distance.
2. Add compact BLE / Wi-Fi source badges and a seen-recently / last-heard age to each live row;
   keep selection keyed by device ID so rows do not jump as RSSI changes.
3. Add an optional strongest/closest-proxy ordering and category visibility toggles, while keeping
   the default discovery order stable and showing when distance is only an RSSI heuristic.
4. When `DeviceKind` wiring is ready, map those kinds to the existing category colors and reuse the
   same legend in Radar and the Devices screen.

**Do not undo:** keep Radar as a single lazy scroll container (no nested scrolling lists) and keep
the database inventory section below live detections. Do not edit `DevicesScreen.kt` or
`core/.../id/` while Claude's recorded identity work is in progress.

### 2026-10-31 (retry) — Codex

- User feedback: the Radar layout looks good.
- Retried `adb install -r` for mapsOnline at `192.168.0.172:5555`; the phone remained reachable
  but ADB stalled without returning install success. Interrupted the transfer rather than continue
  against a 6% battery with no reported power source. No uninstall or data wipe was done.
- User subsequently confirmed Radar scrolling works. After charging, reinstall mapsOnline to verify
  the updated APK on-device; no successful install response was observed during this session.
- The previously observed foreground `MonitorService` ran BLE and Wi-Fi collectors. Leave it
  undisturbed; once the phone is charged, install the existing mapsOnline APK and verify scrolling
  into the saved inventory section.

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

### 2026-10-31 (night) — Claude — WORKING NOW (Codex: please hold edits until my next entry)

Taking: device-database wiring (Room v3→v4: kind/model/stats), Devices category chips, police /
law-enforcement detection with live notifications, Radar category lanes (Codex idea 1). Will keep
the console theme and the single lazy Radar scroll.

### 2026-10-31 (night) — Claude — police detection + live alerts, radar lanes (pushed; NOT yet on device — phone charging)

- **Law-enforcement rule pack v2** (`rules/law_enforcement.json`), identifiers checked against the
  bundled IEEE/SIG registries: Axon/TASER (OUI 00:25:DF, company 0x034D + 0xFC81, UUIDs
  0xFC81/0xFE6B/0xFE6C, names incl. Signal Sidearm), WatchGuard Video, Digital Ally, Utility Inc
  (body/in-car video), Motorola Solutions (7 OUIs, company 0x04EC, UUIDs 0xFD8E/0xFE04), Tait,
  Harris/Sepura/Hytera radios, ALPR (Flock, Genetec AutoVu, Elsag/Leonardo, Neology), agency
  vehicle routers (Cradlepoint, Sierra Wireless AirLink, Havis), Cellebrite / i-PRO / Getac.
  Fixed: Axon 0xFC81 was only matched as a company ID (it is a member *service* UUID).
  Excluded on purpose: WatchGuard Technologies (firewalls), Motorola Mobility, Zebra.
- **Encounter-based live alerts** — `core/alerts/LawEncounterTracker` (+ tests): one loud alert
  per encounter (vibration + AU TTS "Possible police equipment…"), a single ongoing heads-up
  notification on channel `ch_law_live` that updates silently (signatures, dBm, last heard),
  re-alerts only when the first PRIORITY-class signature appears (60 s cooldown), ends after 2 min
  quiet → "Police equipment last detected HH:MM" + `LAW_ENCOUNTER` event (with location) + alert row.
  Tapping opens the app. Settings: "Police equipment alerts" / "Speak police alerts" toggles
  (prefs `law_alerts`, `law_speak`); Settings now scrolls.
- **Radar:** live red police banner at the top while active (amber for 10 min after); unknown-bearing
  dots now sit in fixed category lanes (TRACKER/LAW/CAMERA/VEHICLE/WI-FI/BLE, stable order) with
  radius = RSSI proxy — no more hash-random positions (Codex idea 1). Single lazy scroll kept.
- Verified: core 68/68, app 3/3 (both flavors), both APKs build, offline APK still has no INTERNET.
- **To test on the phone:** Settings → toggles present; Radar banner/lanes; a real Axon/police
  vehicle is the only true positive test — a Wi-Fi hotspot named "AXON-TEST" or "flock-test"
  triggers the SSID rules for a safe self-test.
- **Next (Claude):** device-database wiring (Room v3→v4 kind/model, Devices category chips).

### 2026-10-31 (night, later) — Claude — device database wired; verified on the ZTE P660

- **Room v3→v4** (`MIGRATION_3_4`, data preserved — 153 devices after install): devices gain
  `kind`, `model`, `seenCount`, `rssiMin`, `rssiMax`, `wifiSecurity`, `wifiBand`.
- `core/id/DeviceDb` loads the bundled TSVs; `BleFingerprinter` now resolves Fast Pair model
  names (500), Apple audio models + Nearby Action types, Samsung Galaxy Buds/Watch EasySetup
  models, appearance subcategories ("Watch · Sports Watch"), member-UUID owners → brand.
  `Fingerprint.brand`; Wi-Fi brand from OUI (global BSSIDs only).
- `DeviceIdentityClassifier` takes the fingerprint: rules (law / tracker / camera / fleet) win,
  else the payload kind; category text = kind label. Repository fingerprints every BLE advert and
  Wi-Fi result; Wi-Fi security + band captured.
- **Devices tab:** chips with live counts per kind (Axon / law always shown), Wi-Fi / BLE;
  cards show brand · model · category and "heard N× · min…max dBm · band · security".
  Legacy rows map via `DeviceEntity.effectiveKind()`.
- On-device: install OK, no crash, migration ran, live rows show e.g. "Microsoft · Computer",
  "Logitech · Keyboard / controller", "Apple · Bluetooth item tracker". Police alerts not yet
  triggered in the field (needs a real encounter or an "AXON-…"/"flock…" SSID self-test).
- Tests: core 70/70, app 3/3.
- **Next:** device actions (mine / trust / locate) via `repo.setDeviceFlags`, follower wiring
  (`FollowerScorer` unused), retention purge (never called), CSV export, BLE-spam detection
  (bursts of Continuity / Fast Pair / EasySetup pairing adverts from rotating addresses).

### 2026-10-31 (night) — Claude — DONE, Codex's turn

Claude has finished and released all claimed files (identity wiring, Devices, Radar, police alerts).
Codex: you're clear to edit anything. Phone has `1d9e8d5` installed (mapsOnline, USB adb
YBP660AZBB7000411; Wi-Fi adb 192.168.0.172:5555 when enabled). Suggested next items are in the
previous entry's **Next** list.
