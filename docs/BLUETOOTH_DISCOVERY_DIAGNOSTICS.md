# Bluetooth Discovery Diagnostics — 0.0.8

## Permanent delivery location

Google Drive → Djehuti Movie → Osmo research:
https://drive.google.com/drive/folders/1jWXe3bDu1A-xFa1Bz8W_bVvXVukjRo8g

This is the only build/report/research destination. No testing-folder copies.

## What changed

The existing **Osmo Diagnostics** entry now opens **Bluetooth Discovery
Diagnostics**. The UI no longer constructs the previous connection/notification
controller. This work order's reachable workflow performs scanning only; there
are no selection, pairing, GATT connection, read, write, or movement operations.
The old receive-only protocol implementation is retained, but is not invoked by
this discovery screen. Camera, Movie invitations, audio and streaming code are
unchanged. Namespace and debug signing identity are retained. APK version 0.0.8
(8), not a production release.

The new scanner uses an unfiltered Android callback scan, low latency,
ALL_MATCHES, zero report delay, 20-second foreground window. It includes extended
advertising when Android reports support, otherwise legacy advertisements.
Callbacks and batch callbacks are captured before the previous name/service
recognition rules are applied. Unknown devices are never discarded by those
rules. No cached/bonded-device name lookup is made; name evidence is from the
actual delivered scan record.

Permission waiting is bounded at 60 seconds. Duplicate starts are ignored.
Cancellation, backgrounding and disposal stop/close the scan callback and reject
late callbacks by generation. Disabling Bluetooth during a scan or a revoked
permission produces an explicit scanner failure, retaining earlier observations.
Reports survive activity recreation while the application process survives.

## Report format and privacy

JSON schema `djehuti-bluetooth-discovery`, version 1, diagnostic version 3:

- App/Android versions, random session ID, selected physical condition, UTC start
  and end, elapsed time, scan-start acceptance, actual scan duration, stop reason.
- Hardware/BLE availability, Bluetooth enabled state, checked permission grants,
  Location state/requirements, extended-advertising support and scanner settings;
  raw numeric Android scan error codes when provided.
- Total advertisement observations, distinct session device aliases, matched
  device count, explicit classification and whether Android delivered anything.
- Per-alias first/last UTC and relative observation times, counts, min/max/latest
  RSSI, recognized/rejected counts, advertisement variants and recognition rule.
- Each variant records advertised-name presence/length, fixed coarse DJI/Osmo/OM6
  hints, service UUIDs, manufacturer company IDs, payload lengths, session-keyed
  content tags, service-data UUIDs, flags, AD field types/lengths, legacy and
  connectability flags. Observation detail includes elapsed time, alias, RSSI,
  callback type, variant reference and whether existing rules matched.

Exact names (including unrelated personal device names), permanent addresses,
serials and raw advertisement/manufacturer/service data are deliberately not
exported. Available name/payload fields are captured transiently for recognition
and converted into the safe evidence above. The secret for HMAC content tags is
random per scan, never exported or persisted. Tags correlate variants only
within one session; they are not a tracking identifier across scans. Structural
fields such as company IDs, services, data sizes and name hints can be compared
between conditions, without claiming authenticated identity. No raw-data export
or automatic connection fallback exists.

Bounds: 4,096 address aliases per run; a capacity limit stops the scan and marks it
incomplete. Observation detail retains the last 4,096 entries and explicitly
reports drops; full per-alias aggregate counts survive rollover. Up to 64 variant
summaries per alias are retained with an explicit overflow count. Entries beyond
that still contribute to aggregate/observation counts. Address rotation can make
one physical device appear under more than one alias; the UI says aliases rather
than implying an exact physical-device count. No cross-session address matching
is attempted.

**Export Diagnostic Report** uses Android's sharing menu and a scoped, read-only
FileProvider URI. No file browser or protocol interpretation is required. Share
each report before starting the next scan or exiting the process.

## Classification and limits

- `scanner_unavailable`: permission, hardware, startup, or Android scan failure.
- `no_advertisements_observed`: bounded scan completed without Android-delivered
  observations. This is not proof of radio silence or incompatibility.
- `advertisements_observed_no_dji_match`: observations captured, existing filters
  matched none. The rejected devices remain in the report.
- `possible_dji_device_observed`: name/service hints merit investigation.
- `osmo_identified` is intentionally never emitted: this scan cannot authenticate
  model identity from advertisements. A separate evidence review is required.
- `incomplete_scan`: cancellation, backgrounding or capacity stop, not a misleading
  successful zero-result scan.

`startScan`'s callback API returns void: a return without exception means Android
accepted the request, not that the radio actually observed devices. A later
`onScanFailed` overrides acceptance. The report distinguishes these facts.

The existing Android `neverForLocation` permission flag remains and is explicitly
reported. Android documents that this can filter some BLE beacons. Thus this mode
captures ALL results delivered to the app, not all radio traffic. This order does
not add a precise-location permission request, location access, or change the
app's privacy permission profile. Legacy Android 8–11 still needs foreground fine
Location permission and enabled Location services. OS/vendor policies, screen
state and the Osmo's operating state can affect delivery. No classic-Bluetooth
inquiry, system pairing reset or hidden scanner API is used.

## Exact physical test

1. Install APK 0.0.8 over 0.0.7. Stop WebCam streaming. Open Osmo Diagnostics.
2. Keep the phone unlocked and this screen open. Select **Nearby Bluetooth check**,
   press **Start Discovery Scan**, allow Android permissions, and wait 20 seconds.
   If Android reports advertisements, the application has demonstrated receipt of
   Bluetooth traffic. If none arrive, export that report anyway; do not assume a
   nearby device necessarily advertises BLE continuously.
3. Close DJI Mimo. Power OFF the Osmo. Select **Osmo powered off**, run the scan,
   then **Export Diagnostic Report** → Drive → Djehuti Movie → Osmo research.
4. Power ON the Osmo in its normal operating state. Keep the phone and nearby
   devices in the same positions. Select **Osmo powered on**, scan and export.
5. If its normal documented pairing/discovery condition is available, enter it
   without resetting firmware or bypassing anything. Select **Osmo normal pairing
   condition**, scan and export. Otherwise skip this condition and say so.
6. Export each report immediately after its scan. Then close diagnostics and exit
   the app. Tell the engineer the reports are in the canonical research folder.

Engineering compares timestamps, condition labels, structural company/service/
name/flag evidence, count/RSSI differences and recognized/rejected observations.
A device appearing only in the ON scan is only a plausible candidate; unrelated
radio traffic, address rotation and advertising intermittency remain alternatives.
No user is asked to choose a service or analyze payloads. No further connection
or control work is authorized by this order.

## Verification

Focused tests cover capture before recognition, unknown-device retention,
repeat correlation, times/counts/RSSI/variants, redaction, per-session tags,
classifications, partial failures, malformed AD structures, counter retention,
scan/permission deadlines, cancellation/restart and scan-only route safety.
Existing parser, state-machine, sharing-boundary and Movie pairing tests remain.
Build output is redirected to the canonical build directory. Real hardware,
permissions UI, actual camera operation and Android sharing recipient behavior
remain unverified here. No app or emulator is launched by this task.

References:
https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner

Verified build: Debug assembleDebug + testDebugUnitTest succeeded. All 33 JVM
tests passed (0 failures/errors). APK signature verification passed with the
existing Android debug certificate. APK SHA-256:
12adeff7da80c9e2e6be92ea2981da931cba1d1005ceede5df2748871ac1bb24.
Build log: build/discovery-evidence-build.log.
Signature log: build/discovery-evidence-signature.log.

