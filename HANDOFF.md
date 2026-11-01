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

### 2026-11-01 — Codex — compact layout pass

- Radar's scan-session panel now keeps Start / Save / Clear in a compact row and puts the longer
  explanation behind a disclosure. Session actions and the clear confirmation behavior are intact.
- Devices' type and band filters now collapse on demand (and remain expanded when active filters
  need to be visible), giving the inventory cards more room. Tightened card spacing slightly.
- Reviewed Sweep, Map, and Protect on the ZTE P660; those layouts already read clearly at this
  screen size, so left them in place.
- Verified `:core:test`, `:app:testMapsOnlineDebugUnitTest`, `assembleMapsOnlineDebug`, and
  `assembleOfflineDebug` with cached Gradle 8.7 dependencies. Installed mapsOnline using `adb
  install -r` without uninstalling. Direct launch succeeded; on-device screenshot confirmed the
  compact Radar session panel and collapsed Devices filter. Device data remained visible.
- No schema, detection, or scanner behavior changes. Existing Kotlin warning: unused `facing`
  variable in RadarScreen.

**Next:** Claude can continue with the tracker false-positive review and detection quality; preserve
the Radar single lazy scroll and the new compact session / collapsible filter controls.

### 2026-11-01 — Codex — tracker classification reliability

- Removed Apple company ID 0x004C by itself from the broad tracker rule. Apple uses this company
  ID for many ordinary BLE products; actual Find My matching remains in `TrackerParser` via the
  0x12 manufacturer payload or the specific 0xFD44 accessory service.
- Removed Google FEAA from the broad tracker rule because the UUID is shared with Eddystone.
  `TrackerParser` remains authoritative and only accepts Google Find My frame types 0x40/0x41.
- Added regression tests for both false-positive cases and retained positive tests for Apple
  Find My service UUID, Apple Find My payload, and Google FMDN payload detection.
- Verified JSON validity and `:core:test`, `:app:testMapsOnlineDebugUnitTest`,
  `assembleMapsOnlineDebug`, `assembleOfflineDebug` (Gradle 8.7, offline dependencies). Installed
  mapsOnline with `adb install -r`, launched the app, confirmed the saved device inventory remains
  present (215 devices), and observed no AndroidRuntime fatal exception in recent logs.
- No schema or scanner changes. Category rules now deliberately omit Apple/Google cases that need
  payload-aware parsing; do not re-add company 0x004C or shared service UUID 0xFEAA as standalone
  tracker predicates.

### 2026-10-31 (late) — Codex — feature review and additions

**Similar-app review (ideas only; no external code copied):**
- BLE Studio's read-only advertisement inspector shows raw bytes alongside parsed services,
  manufacturer payloads, and signal metadata: https://github.com/robyrawat/ble-studio
- AirGuard reduces tracker false positives by requiring recurring sightings and changed
  locations before calling out a possible follower: https://github.com/seemoo-lab/AirGuard
- WiFiAnalyzer offers band/security filters and channel/time graphs; WiGLE emphasizes local
  search and explicit CSV/KML/GPX exports: https://github.com/VREMSoftwareDevelopment/WiFiAnalyzer
  · https://github.com/wiglenet/wigle-wifi-wardriving

**Implemented:**
- BLE device details now preserve the raw advertisement as hex and expose it behind an
  expandable inspector; both BLE and Wi-Fi details include the fingerprint evidence used for
  their best-effort identification. No schema migration was needed (`radioDetails` already exists).
- Device details now include an on-demand co-movement analysis for tracker candidates. It uses
  GPS-tagged sightings, coalesced user route samples and `FollowerScorer` thresholds; it does not
  create an alert by itself or claim that a nearby tracker is following the user. Mark Mine / Trust
  controls use `repo.setDeviceFlags`; those exclusions suppress tracker candidate alerts and are
  honored by movement scoring.
- Radar has Start / Save / Clear session capture controls using the existing `sessions` table and
  `observations.sessionId`. Save closes the session and writes a session event. Clear confirms and
  deletes only observations and metadata for the active session; device inventory is retained.
- No database schema change or network feature was added.

**Verified:** core 71/71, mapsOnline app 3/3, both APK flavors build successfully; offline flavor
manifest unchanged. Installed mapsOnline with `adb install -r` over USB on the ZTE P660 at 36%
charging. On-device screenshot showed Radar live signals and the Session panel. Session actions
were not triggered on the user's database; tracker-detail actions were compile/test verified.

**Next ideas:** add a Wi-Fi channel congestion/time graph after storing channel as a typed field
(requires an explicit Room migration), and user-triggered CSV/KML session export via Android's
document picker with no background upload. Keep follower alerts separate from signature
identification; require repeat locations plus actual user travel.

### 2026-10-31 (late night) — Claude — IN PROGRESS (Codex: please hold edits)

Pushed so far (core 74/74): law pack **v3** (+ speed radar/lidar incl. Decatur OUI, Toughbook MDT,
emergency lighting names, Skydio police drones, Peplink/Pepwave vehicle routers, Harris Gov / Zetron);
fleet pack **v2 = vehicle GPS trackers** (Teltonika, Geotab, Samsara, Ruptela, Queclink, Jimi/Concox,
CalAmp, LoJack, Suntech, Pointer, Zonar, Verizon Connect, Navman, Invoxia, Katch — company IDs, UUIDs,
OUIs); `DeviceKind.DRONE`; `df/RssiCentroidLocator` (RSSI-weighted position from GPS sightings; works
with a few metres of walking; `directional=false` when stationary) + tests.

Working on (user requests): **Radar → north-up location plot** with real positions (drive-by fix or
RSSI centroid + uncertainty circle), All/one-category filter, per-device locate chart; then drone
Remote ID detection, background follower alerts (+ "police vehicle following"), home watch, evidence export.

### 2026-10-31 (late night) — Claude — surveillance-victim features + thorough police detection (pushed)

All compile + tests green (core 77/77, app 3/3); offline APK still has no INTERNET. **Not yet
installed on the phone** (phone was charging) — next step: `adb install -r` mapsOnline and verify.

- **Police detection v3** (`rules/law_enforcement.json`, 14 rules): adds speed radar/lidar
  (Decatur OUI + vendor names), Toughbook MDTs (Panasonic Connect OUIs), emergency lighting/siren
  names, Skydio police drones, Peplink/Pepwave vehicle routers, Harris Gov / Zetron. Background
  follower check escalates law-enforcement kinds to `LAW_VEHICLE_FOLLOWING`.
- **Vehicle GPS trackers** (`rules/fleet.json` v2): Teltonika, Geotab, Samsara, Ruptela, Queclink,
  Jimi/Concox, CalAmp, LoJack, Suntech, Pointer, Zonar, Verizon Connect, Navman, Invoxia, Katch.
- **Radar = real locations** (`ui/screens/LocationScope.kt`): north-up metre plot centred on the
  user; devices at a drive-by fit or `df/RssiCentroidLocator` position (RSSI-weighted GPS sightings,
  `directional` only with ≥8 m spread) with uncertainty circles; others listed by RSSI distance.
  Category filter chips (pref `radar_filter`). Tap → per-device locate chart (heat dots, you,
  estimate ± radius, "~N m to the NE"). Replaces the category-lane scope.
- **Drones** (`core/id/RemoteIdParser`): ASTM F3411 Remote ID over BLE 0xFFFA and Wi-Fi beacon
  vendor IE FA:0B:BC (API 30+ `informationElements`), DJI DroneID IE presence; alert with serial,
  distance, height, pilot distance/direction; drone ◆ + pilot P on the scope.
- **Follower alerts** (`repo.checkFollowers`, every 2 min): only after ≥1 km of user travel in 3 h;
  `ObservationDao.followerCandidates` → Codex's `analyzeFollower` → `POSSIBLE_FOLLOWER` alert
  (once / 30 min / device) + FOLLOWER event; Radar "Travelling with you" card.
- **Home watch** (Settings): Set/Move/Clear Home (150 m); visits ≥20 min become baseline sessions
  (geofence + baselines tables, `__visits__` counter row); after 3 visits, concerning newcomers
  (tracker / GPS tracker / camera / law / drone / hotspot / non-random Wi-Fi) heard 6+ times over
  10+ min → `NEW_AT_HOME` (once / day / device).
- **Evidence export** (`app/export/EvidenceExporter`): HTML report + sightings CSV + alerts CSV via
  FileProvider (`exports/` cache only) and the share sheet; Devices card "Export evidence report",
  Event Log "Report".

Codex: free to take a turn. Ideas: retention purge (still never called), BLE-spam detection,
on-device verification of the above, follower-alert action buttons (Mark mine from notification).

### 2026-10-31 (late night 2) — Claude — protective toolkit (pushed; phone was charging, NOT yet verified on device)

All compile + tests green (core 96/96, app 3/3, both flavors). New tools live under **More**:
- **Hacking tools / attacks** (`rules/attack_tools.json`, `core/cs/AttackDetectors.kt`): Flipper Zero
  (SIG 0x0E29, OUI 0C:FA:22), WiFi Pineapple SSIDs, ESP32/8266 attack firmware, Pwnagotchi (also beacon IE
  222), O.MG / keylogger implants, RFID cloners; BLE pop-up spam burst detector; evil-twin Wi-Fi detector
  (security downgrade / randomised BSSID for a known SSID, seeded from inventory).
- **Skimmer check** (`scan/ClassicScanner`, `core/cs/SkimmerScorer`, `SkimmerScreen`): on-demand Classic BT
  inquiry, Bluetana-style multi-signal score with every point explained; HIGH → red flashing alert.
- **Stalkerware & spyware check** (`scan/AppScanner`, `core/cs/Stalkerware.kt`, `StalkerwareScreen`,
  `resources/stalkerware/*`): AssoEchap indicators (160 families, packages, certs, 3,228 APK hashes) + MVT
  STIX (mercenary spyware) — 3,461 APK SHA-256s; behaviour scoring. Needs QUERY_ALL_PACKAGES. Safety note.
- **Discreet mode** (`discreet/Discreet.kt`, `ui/LockScreen.kt`): Calculator launcher alias, calculator PIN
  lock (PIN then '='), phone PIN/pattern unlock (device credential), relock, FLAG_SECURE, shake-to-hide,
  neutral notifications. **MainActivity is now a FragmentActivity; launcher filters live on activity-aliases.**
- **Car check** (`CarSweepScreen`): 60 s walk-around, ranked trackers / GPS trackers / strong unknowns,
  Hunt handoff via `vm.huntRequest`.
- **Encounter history map** (`HistoryPanel.kt`, Map → History): event layer + repeat places + timeline.
- **Quick-settings tile + widget** (`widget/`), status from `repo.publishStatus()`.
- **Camera finder** (`CameraFinderScreen`): Camera2 IR / lens-glint spot detection. CAMERA permission.
- Also: ambulance/fire pack, TASER rule, traffic cameras, camera pack v2, flash/torch alerts (earlier entries).

Next (anyone): on-device verification of all of the above; retention purge (still never called);
generic Android malware feed (abuse.ch needs an API key).

### 2026-11-01 — Claude — IN PROGRESS (Codex: please hold edits until my next entry)

Working on: Devices-tab filters (time window, status, sort, multi-category; state in the
ViewModel), ingest hot-path trims (single rule evaluation per advert, timed room-sweep pruning,
lazy radio-details string), device list published from the in-memory cache instead of a full
SQLCipher re-query every flush, and wiring the retention purge. No schema changes.

### 2026-11-01 — Claude — DONE (Codex's turn): filters, optimisation, cross-links, map/locate dedupe

Verified on the ZTE P660 (mapsOnline installed with `adb install -r`, data preserved, no crashes).
Tests: core 102/102, app 15/15; both APK flavours build; offline APK has no INTERNET permission.
No schema changes (Entities.kt / RfWatchDatabase.kt untouched).

**Devices tab:** filter state lives in the ViewModel (`DeviceFilter`, `applyDeviceFilter` in
`ui/DeviceFilters.kt`, computed off-main). Time window (Live 2 min / 1 h / 24 h / All), status chips
(Threats / New 24 h / Named / Hide mine-trusted, AND), multi-select kinds (Axon / law always shown),
Wi-Fi/BLE, sort menu (Discovery / Strongest / Last heard / Most heard / Name). "Approx. closest"
removed (it sorted exactly like Strongest).
- `isThreat`: excludes own/trusted, Find My "owner nearby", and everyday Apple kinds flagged NOTABLE.
  **Rule-pack issue for whoever owns detection:** `trackers.json` `apple_findmy` matches companyId 76
  alone, so every iPhone/Mac/AirPod is classified NOTABLE (63 of 63 "threats" at home). Consider
  requiring the 0xFD44 service UUID or a Find My payload. Not changed here (affects alerts).

**Cross-linked features:** `DeviceDest` / `DeviceNav` / `DeviceLinks` (ui/DeviceNav.kt). `vm.openDevice(id,
dest)` → MainActivity switches tab; Devices (Details), Radar (Locate panel, rebuilt from the stored row
when not live), Sweep (Hunt via existing huntRequest), Map (centre + select). Screens consume the
request only *after* their scroll — consuming first re-keys and cancels the LaunchedEffect.
Map has a "Devices filters" chip (shows `deviceView.visible`).

**Map / Locate duplicates — measured first** (debug-only `DuplicateReport`, logcat tag `RFWatchDup`,
runs on Map open in debug builds): 166 devices on 23 points (stack up to 29); Locate 88–94 % of dots
were repeats of the same phone fix; 5 SSIDs on 2 BSSIDs (dual-band); ~25 BLE rows look like rotations.
- Map: one numbered marker per ~10 m cell (`stackKey`, 1e-4°), colour = most notable member; tap →
  list → device card with links. Clusters sum a `devices` property (counts stay in devices).
  Cluster max zoom 17. Header/legend/Help toggle, overlay "◎ Me" / "⤢ Fit" (were "My location"/"Fit all").
- Locate: `mergeSightings` → one dot per GPS point (strongest RSSI, count → dot size); `LocateData.hearings`.
- NOT merged: rotating BLE identities and dual-band BSSIDs in the inventory (heuristic; could hide
  distinct devices). Candidate next step: show "probably same as …" hints rather than merging.

**Radar:** "Distance only" now plots every device on one log-scale strip (0.5–100 m), then the
individual rows (10 + "Show all N"). No more "+N more below".

**Performance:** device list served from the write-behind cache (`deviceFlow`, published after each
flush / flag change / wipe) instead of a full SQLCipher re-read every 3 s; one rule pass per advert
(`RoomSweep.analyze(facts, hits)`); room-sweep pruning on the 1 s publisher; BLE parse/fingerprint/
radio-details reused for byte-identical adverts (`bleParseCache`); `core.math.Hex` replaces per-byte
String.format.

**Retention purge now runs** (30 s after monitoring starts, then every 6 h): observations, alerts and
events older than Settings → retention; devices and mine/trusted marks kept; logs `RETENTION_PURGE`.

**Next ideas:** "probably same device" hints for rotations / dual-band; per-device stats panel
(first/last seen, places, RSSI over time); CSV/KML export via document picker; fix the apple_findmy rule.

### 2026-11-01 (morning) — Claude — IN PROGRESS (Codex: please hold edits until my next entry)

Organisation / ease-of-use pass: Radar verdict + tappable status chips (open Devices with a preset
filter), Radar inventory section replaced by a link, one shared compact device row with friendly
names (Radar live / Find / Devices), "Sweep" tab renamed "Find" with search, per-device stats in
Details, then device nicknames via an explicit Room v4→v5 migration (encrypted, wiped with data).
Keeping Codex's single Radar lazy scroll, compact session row and collapsible filters.

### 2026-11-01 (morning) — Claude — DONE (Codex's turn): organisation pass

- Room DB v4→v5: `devices.nickname TEXT` (explicit MIGRATION_4_5). Verified on ZTE: 225 → 231 devices
  after `adb install -r`, no crash. Encrypted backup of the pre-migration DB in ~/Projects/RFWatch-backups.
  `onDevice` carries `nickname = previous?.nickname`; write via `repo.setNickname` (cache path).
- Shared `ui/DeviceRow.kt`: `friendlyName` (nickname > advertised > "Brand kind ·MAC tail"),
  `DeviceRowCompact`, `toRowInfo` for DeviceEntity and LiveContact. Used by Radar live list, Find, Devices.
- Radar: verdict header ("Nothing flagged right now" / "N to check", same `isThreat` as Devices),
  status chips open Devices with preset filters (`vm.openDevicesWith`), inventory section replaced by a
  link. Kept Codex's single lazy scroll / compact session row / collapsible filters.
- Tab "Sweep" renamed "Find" (tap scripts: label changed); Find has search, trackers/flagged first.
- Devices: compact rows; opened card has Rename / Close.
- Tests + both flavours built. Radar/Find new layouts NOT screenshot-reviewed yet (laptop battery).

### 2026-11-01 (afternoon) — Claude — DONE: evil-twin false positive (router-derived BSSIDs)

- Real alert today: a neighbour's Telstra (Vantiva) modem SSID "also broadcast" from a BSSID that is the
  router's own address with the locally-administered bit set and the last byte +8 (same WPA2) — its
  second radio / virtual AP, flagged only because the address "looks randomised".
- `EvilTwinDetector.derivedFrom(bssid, known)`: multicast bit equal, octets 2–3 identical, octets 4–6
  within 64. A derived address no longer counts as "randomised" evidence. **A security drop still
  always alerts**, and unrelated randomised BSSIDs still alert. Tests use changed addresses.
- Verified: core 110/110, app 15/15, both APKs build, offline APK has no INTERNET.

### 2026-11-01 (afternoon) — Claude — DONE (Codex's turn): Radar dots readable + no fake "located"

**Measured first** (new debug-only `RadarReport`, logcat tag `RFWatchRadar`, once per process when Radar
opens; counts/distances only): today's fixes median 2 m, p90 6 m, 2 % worse than 20 m — GPS quality and
the 120-sample window were NOT the problem ("whole day" tally = "now" tally). Real causes:
1. A single poor fix (~56 m away, ±80–100 m) inflated min/max spread → 23 of 27 devices "located" while
   standing at home (e.g. 7 sightings in 34 s with "56 m spread").
2. The plot zoomed to fit uncertainty circles (50 m scale) and drew every circle → dots piled in a few
   metres under ~20 overlapping rings; looked like "no dots".

**Fix:** `RssiCentroidLocator` ignores fixes worse than 20 m (`MAX_FIX_ACCURACY_M`) for centroid, spread and
median accuracy; if fewer than 3 good fixes remain the estimate is returned but never directional (global
`MAX_FIX_ACCURACY_M = 100` for stored observations unchanged — follower/home/Map still use them).
Location scope zooms to the 80th-percentile dot distance (min 20 m), pins farther dots hollow on the rim in
their direction, and draws the uncertainty circle only for the selected device. On the ZTE: 27 → "7 located ·
13 by distance", dots separated on a 20 m scale. Tests: core 112/112 (+2 locator), app 15/15, both APKs,
offline has no INTERNET.

**Known limit (told Jorden):** a weighted centroid can only put a dot on the path walked, where that device
was loudest. Live position of a moving phone in the yard is not possible from one phone (no direction, RSSI
±50–100 %, MAC rotation). Ideas offered: "approaching" trend alert for unknown devices at home; multi-node
ESP32 sensors for real live yard tracking.

### 2026-11-01 (evening) — Claude — IN PROGRESS (Codex: please hold edits until my next entry)

Adding (1) "probably same device" hints (BLE address rotations, router multi-radio BSSIDs) — display-only,
no merging, not fed into alerts; (2) on-demand ultrasonic beacon check (mic, Protect hub, 17–22 kHz).

### 2026-11-01 (evening) — Claude — DONE: "may be the same device" hints (display only)

- `core/cs/SameDeviceHints`: BLE address rotations linked only when **mutual and unique** (same signature with
  ≥2 of brand/model/kind/name, next address starts ≤5 min after the last went quiet — ≤2 min and ≤8 dB for
  generic "Apple · phone"-type signatures — up to 60 s overlap, signal step ≤12 dB using the next row's FIRST
  RSSI via `ObservationDao.firstRssi`). Wi-Fi: derived/same-first-5-octet BSSIDs; same (normalised -5G/_EXT)
  SSID → probably, other SSID only when the tail is ≤16 apart → possibly.
- Device details card: "MAY BE THE SAME DEVICE" section with tappable links. Records are NOT merged and hints
  are NOT used by follower / home / alerts.
- Measured on the ZTE (debug `RFWatchDup` line "SAME-DEVICE hints"): first cut gave 839 pairs (730 vague);
  tightened → 286 (BLE 25, Wi-Fi 261 multi-radio routers). Today's DuplicateReport shows 257 loosely
  chained BLE rows over 10 signatures, so the hints are deliberately conservative (under-link) — low false links.
  The count runs once per process off the main thread (whole-inventory pass is heavy).
- Tests: core 130/130 (+12), app 15/15, both APKs, offline no INTERNET. Next: ultrasonic check (still Claude).

### 2026-11-01 (evening) — Claude — DONE (Codex's turn): ultrasonic beacon check

- `core/cs/UltrasonicDetector`: 4096-pt Hann FFT, 50 % overlap, 17–22 kHz (capped below Nyquist), per-frame
  median noise floor, narrow peaks ≥15 dB, tracked across frames → STEADY (≥90 % present, ≤2 bursts —
  usually electronics), BEACON_LIKE (≥3 bursts, or tones that alternate = FSK-style data), BRIEF. Faint tones
  ≥25 dB below a neighbour within 1.5 kHz are dropped as its distortion spurs (found on-device). Also reports
  band vs 1–8 kHz floor so a deaf mic band shows as "may not hear this band", not an all-clear.
- `UltrasonicScreen` (Protect hub → Check a place): 20 s on demand, screen-only (ON_STOP cancels → "stopped,
  no result"), AudioRecord off-main, UNPROCESSED if supported else VOICE_RECOGNITION, 48 k → 44.1 k fallback,
  released in finally; audio never stored. Event `ULTRASONIC_CHECK`; hub status line. Debug logcat `RFWatchUltra`.
- New permission RECORD_AUDIO (runtime; UI tells the user to pick "Only this time"). Stalkerware check
  already skips RFWatch's own package (AppScanner).
- **Verified on the ZTE with real tones from a MacBook speaker:** VOICE_RECOGNITION source, 48 kHz. Quiet room
  → no tones (band floor ~10 dB under speech band — mic does hear 17–22 kHz). 19 kHz 200 ms/600 ms bursts →
  BEACON_LIKE +62 dB, 31 bursts. Steady 19 kHz → STEADY only (before the spur filter it also produced false
  "beacon-like" spurs at +19–23 dB). Test runs are in Jorden's event log at ~16:33–16:38.
- Tests: core 131/131 (+8), app 15/15, both APKs, offline has no INTERNET.
- Follow-up: real permission dialog verified ("Only this time" → check runs). Debug SAME-DEVICE count gated once per
  process and moved off the main thread (it froze Map open). NOTE: the 16:33–16:38 ULTRASONIC_CHECK test events
  (incl. one false "beacon-like" from before the spur filter) are in the event log and in full evidence exports.

### 2026-11-01 (night) — Claude — DONE (Codex's turn): prefer GPS over network fixes

- `LocationFixHolder.update`: a non-GPS fix is ignored while a GPS fix that *arrived* ≤5 s ago (monotonic
  clock, injectable for tests) is at least as accurate; otherwise network/fused fixes are used as before
  (indoors keeps working). `GpsFix.provider` added (default null). Affects every new sighting (Map, Locate,
  follower, home) — ~2 % of today's sightings had ±100 m network fixes. Stored history unchanged.
- Tests: core 131/131, app 20/20 (+5 LocationFixHolderTest), both APKs, offline no INTERNET. Installed on the
  ZTE: monitor running, no crashes.

### 2026-11-01 (night) — Claude — Room sweep Hunt button + hunt text fixes (Claude continuing: Hunt reliability)

- Room sweep cards: primary **Hunt** button (`vm.openDevice(key, HUNT)` → Find hot/cold); Locate renamed
  "Locate (outdoors)" (path-loss fit needs 100 m+). Header explains: walk ~2 min, then Hunt.
- Hunt panel: "last heard 1793517239 s ago" (lastHeardMs = 0 before first sample) → "Not heard yet…";
  waiting text no longer 44 sp (overlapped itself).
- Verified on ZTE: Room sweep → Hunt opens Find with the target. Next: Hunt drop-outs (measuring).

### 2026-11-01 (evening) — Claude — BLE scan stall watchdog (Hunt drop-outs)

- **Measured on the ZTE:** the always-on LOW_LATENCY scan stays "ongoing" in `dumpsys bluetooth_manager` but
  stops receiving results ~5 min after each start (system result counter frozen; app got 0 callbacks for
  minutes, screen on, app foreground). That was the Hunt "takes forever / drops out" problem (and likely the
  old "only Wi-Fi detected" symptom).
- `BleScanner` watchdog: Bluetooth on + SCANNING + no result for 20 s → restart, ≥30 s apart. 12-min test:
  2 stalls (17:51, 17:56 — each ~5 min after a start), both revived within ~20 s (0 → 112–115 results/10 s).
- Tried and removed: an address-filtered hunt-only scan — no more target hits than the main scan, and while it
  ran the main scan only reported the target (controller filter applied globally).
- Debug logcat `RFWatchHunt` (10 s BLE callback/address counts, hunt gaps) and `RFWatchBle` (watchdog).
