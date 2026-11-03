# Osmo read-only service inspection — 0.0.9

Permanent artifact destination: Google Drive → Djehuti Movie → Osmo research.
https://drive.google.com/drive/folders/1jWXe3bDu1A-xFa1Bz8W_bVvXVukjRo8g

## Physical test

1. Install 0.0.9 over 0.0.8; the package and debug signing identity are retained.
2. Mount the phone and power on the Osmo. Keep it stationary and disconnected from
   the existing phone Bluetooth session, as in the successful discovery test.
   Keep DJI Mimo closed. Do not forget/unpair/reset or try undocumented buttons.
3. Stop WebCam streaming, open Osmo Diagnostics, then **Scan for Osmo**. Allow
   Android permissions if requested and wait for the 20-second scan.
4. One candidate is selected for you, without connecting. Multiple candidates
   require selection; if uncertain, export the scan report and stop.
5. Press **Connect to Osmo**. Approve only a normal Android authorization prompt
   for your intended gimbal if shown. The app does not change bonding itself.
6. When Connected appears, press **Discover Services** within 30 seconds. Leave
   optional standard-information reads OFF for the first metadata-only test.
7. Discovery (and opted-in reads, if any) ends with automatic disconnection.
   Expand **Show service inspection results** if desired. No UUID copying needed.
8. Press **Export Diagnostic Report** and save to the canonical research folder.
   Failures also produce a report. **Disconnect** is available while active.
9. Close diagnostics and exit the app. Stop if unexpected movement occurs.

## Implementation and safety

The existing comprehensive scanner captures observations before recognition.
Connectable candidates with an OM6 name hint or FFF0 are retained only in RAM.
A completed, stopped scan is required before retrieving a candidate handle.
Connection starts only from the explicit button. No automatic retries, arbitrary
device selection, background reconnection or forced disconnection of another
Android service is performed. Camera, recording, WebRTC and Movie pairing code
are unchanged; the diagnostic entry remains disabled while streaming.

`OsmoServiceSession` is a pure lifecycle with a fake-testable Port;
`AndroidOsmoInspection` adapts standard Android GATT APIs. Callbacks are serialized
and guarded by client identity and generation. The old notification-enabling
diagnostic controller is not instantiated by the current interface.

Limits: connection 15 seconds; waiting for Discover 30; discovery 10; each optional
read 5; at most 8 reads; entire session 90; disconnect acknowledgement 3 seconds.
Failure, cancellation, backgrounding, Bluetooth-off and disposal release the
client. A local close return is distinguished from an observed remote disconnect.
Cleanup failures are explicit and prevent another client from being opened.
OS bonding state is observed initially and during the session. An authorization
screen that backgrounds this foreground-only diagnostic can end the session;
the user may retry manually after normal OS authorization. No bypass is provided.

Optional reads are OFF by default and require the checkbox before Discover:

| Service / characteristic | Permitted decoded data |
| --- | --- |
| 1800 / 2A01 Appearance | Exactly 2 bytes, numeric appearance |
| 180F / 2A19 Battery Level | Exactly 1 byte, 0–100 percent |
| 1812 / 2A4A HID Information | Exactly 4 bytes, HID version/country/flags |

Both the lifecycle and Android adapter enforce these pairs and the READ property.
Unexpected format, rejection, permission errors and timeout are reported without
exporting arbitrary value bytes. No device names, serials, proprietary values,
HID input reports or descriptors are read. No characteristic/descriptor writes,
notification enables, DJI packets, initialization, calibration, firmware, motor
commands, createBond/removeBond or hidden security APIs exist in this path.
Hardware-initiated behavior and stationarity still require physical observation.

## JSON and interpretation

Schema `djehuti-osmo-service-inspection`, version 1, includes app/Android versions,
session ID, UTC and elapsed times; selected alias and advertisement evidence;
initial bond/GATT/Bluetooth state; bond changes; connection/discovery requests,
acceptance and completion; Android statuses; complete service UUID/instance/type,
characteristic UUID/instance/properties/operation names/permissions, descriptor
UUIDs/permissions; read consent/results; and final disconnect/close status.
Event detail is bounded at 1,000 rows with a dropped count. Inventory is not
truncated; optional reads have the explicit 8-operation limit. Remote permission
metadata can be zero/incomplete and is not proof of actual server security.

Assessment fields highlight FFF0 and HID 1812, including HID Report Map and Report
characteristic presence. This establishes structure only, not working live input
or motor control. Outcomes distinguish connection failure, discovery failure,
both succeeded, GATT inconclusive, authorization required and incomplete session.
An operation never completed cannot be marked completed. GATT status cannot
reliably establish that another Android service occupies the device, and GATT
failure does not rule out Classic Bluetooth or another interface.

Exports omit MACs, exact names, serials, unrelated devices, raw payloads and pairing
material. They use the existing scoped read-only FileProvider and Android sharing
menu. If connection was attempted, its terminal report is exported; otherwise the
scan report is available. Export before leaving the application process.

## Verification limits

Simulated-port tests exercise explicit authorization, rejected candidates,
duplicate starts, full enumeration, safe reads, unsafe-read rejection, timeouts,
Android errors, bonding/auth observations, cancellation and cleanup failures.
Source guards reject write/subscription/bonding APIs in the current connection
adapter. Existing discovery, parser, lifecycle, sharing and Movie pairing tests
also run. No app/emulator/physical device is launched. Real phone permissions,
pairing UI, GATT behavior, reads, sharing and camera return require Executive
Director testing. No push, merge or motor-control work is authorized.

References:
https://developer.android.com/reference/android/bluetooth/BluetoothGatt
https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html
https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/BAS_v1.1/out/en/index-en.html

Verified: Debug assembleDebug and testDebugUnitTest passed; 45 JVM tests, zero
failures/errors. APK signature verification passed with the retained debug key.
APK SHA-256: 5228b9b478018a09ce2819acf482ae9358647be35ae2bf630af4d255961b8ccf.
Logs: build/osmo-service-build.log and build/osmo-service-signature.log.

