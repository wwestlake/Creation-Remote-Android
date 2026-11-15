# Osmo Mobile 6 Bluetooth investigation

Prepared November 2, 2026 by Codex for the Executive Director and Chief Engineer.
Order 1 only: source research, receive-only protocol analysis and an isolated
Android BLE diagnostic. No production gimbal controls or motor actuation.

## Assessment

There is sufficient evidence to investigate standard BLE discovery and GATT
communication, but none of the reviewed community projects establishes OM6
compatibility. DJI developer support says MSDK v5 does not support the Osmo Mobile
series. This is an SDK limitation, not proof that independent BLE is impossible.
The initial research document was read first and preserved unchanged [R1].

Confirmed from source: the older-model project names FFF0/FFF4/FFF5 and reports
OM2/3/4 tests. OM SE captures suggest a related transport, but their framing prose
and packet-building script contain contradictions. No legacy control packet has
been sent, and no physical OM6 connection is claimed.

## Existing Android infrastructure and implementation

The application uses Kotlin/Compose, minimum API 26 and target API 35. Existing
camera, audio, WebRTC and Movie pairing code are retained. No BLE dependency or
permission existed before this order. The new implementation uses Android's
platform BluetoothManager, BLE scanner and BluetoothGatt; no DJI SDK or new runtime
library is added. The Android namespace/signing identity is retained.

A Gimbal BLE diagnostics button is available only when streaming is stopped and
the QR scanner is inactive. The diagnostic has a separate controller and screen;
it does not modify the Movie invitation, authentication or streaming protocol.
Leaving that screen closes its BLE resources. Backgrounding or opening Android's
export picker disconnects BLE; the redacted journal stays in memory for export.

The utility performs a bounded 15-second scan (at most 100 displayed devices),
shows transient names, RSSI, advertised service UUIDs and manufacturer company IDs,
then lets the operator choose a device. Names are identification hints, not proof
that a device is an OM6. Standard GATT connection and service discovery have
bounded timeouts. The inventory lists service/characteristic UUIDs and read,
write, write-without-response, notify and indicate property bits; these bits do
not imply application-level permission or support for a particular command.

Subscription is explicit, one operation at a time. Only the standard 0x2902 CCCD
is written (enable notifications or indications) after local subscription setup.
There are no characteristic reads/writes, motor commands, custom authentication,
firmware operations, packet fuzzing or pairing bypass. GATT status, discovery,
subscription and timeout failures are shown to the operator. Generation guards
ignore old callbacks; disconnect closes GATT and clears parser fragments.

Android 12+ requests Nearby devices scan/connect permissions, with neverForLocation.
Android 8-11 requests legacy Bluetooth permissions plus foreground fine-location
permission and requires location services for scanning. Bluetooth hardware is
optional so ordinary WebCam remains installable. No advertising/background-location
permission or Bluetooth foreground service is introduced [R6, R7].

## Verified framing and command limitations

The receive-only parser independently implements DUML v1 framing. It supports
fragmented/coalesced input, resynchronization, header and frame checksums, and a
maximum 1023-byte frame. Its buffer cannot grow without bound. Invalid frames are
not interpreted as commands. Parsing is separate for each subscribed channel.

| Field | Offset or rule |
| --- | --- |
| Start | Byte 0 is 0x55 |
| Length and version | Low 10 bits of little-endian bytes 1-2 are total length; byte 2 shifted right 2 is version 1 |
| Header checksum | One byte at offset 3; reflected CRC-8 polynomial 0x8C, initial 0x77, over bytes 0-2 |
| Sender and receiver | Raw routing bytes 4 and 5; model-specific naming is not assumed |
| Sequence | Bytes 6-7, little endian |
| Flags and command | Byte 8 flags; byte 9 command set; byte 10 command ID |
| Payload | Bytes 11 through length minus 3; omitted from export |
| Frame checksum | Last two bytes, little endian; reflected CRC-16 polynomial 0x8408, initial 0x3692, over all preceding bytes |

The published 21-byte fixture 551504a902044700400414c20100002000050feb63
validates header 0xA9 and frame checksum 0x63EB. Starting the CRC-16 calculation
with that frame's four-byte header yields 0xDF0C, matching the older project's
body-only calculation. This validates the framing math for that fixture, not OM6
support for command set 0x04 / command 0x14 [R2].

The older implementation connects with Bleak, subscribes FFF4, writes FFF5 and
chunks writes to MTU minus 3. Its rotation code uses 0x04/0x14 for absolute/relative
rotation and 0x04/0x0C for speed, with signed angle fields and mode/time bytes.
The example uses a fixed sequence in generated commands, not a general
acknowledgment/session manager. Its parser itself does not validate checksums;
our receive-only parser adds both checks [R2].

The OM SE report describes movement 0x23/0x09, echoed sequence responses, 0x40/0x80
flags, SMP encryption and a configuration burst. These are claims about its
capture, not verified OM6 requirements. Its prose incorrectly assigns two header
CRC bytes, while shown prefixes align with a one-byte CRC-8. Its script emits a
two-byte CRC-16 header and contains guessed/truncated payload reconstruction.
It must not be treated as a validated control implementation. Reported ATT handles
are connection/database-specific and are not OM6 UUID constants [R3].

No reviewed source establishes OM6 device authentication, initialization,
notification channel, hardware-button events or a supported motor command.
The diagnostic performs no undocumented initialization or automatic command ACK.
No notifications may simply mean that additional initialization is required.

## Export privacy and engineering evidence

Exports use a whitelist-based JSON journal with relative monotonic times,
session-only device aliases, result codes, UUID inventory, property bits, received
byte counts and checksum-valid DUML header fields. The event ring retains 1000
entries and reports drops. A separate bounded inventory survives event rollover.
Raw names, MAC addresses, manufacturer/service-data values, characteristic values,
serials, payload bytes and pairing material are not exported or sent to Logcat.
Raw incoming bytes are transient parser input only and are cleared on disconnect.

This deliberately limits payload reverse engineering. The log can establish GATT
structure and framing observations, but cannot reconstruct unknown payloads or
authentication exchanges. A future authorized capture plan must address sensitive
data review before any raw export. A parsed packet is never an OM6 capability claim.

## Validation and artifact

The build uses assembleDebug testDebugUnitTest, one worker and development-drive
caches/temp paths. Final results, signature, hash and local commit are recorded in
the accompanying completion report and PDF build summary. Tests cover the
published packet, fragmentation/coalescing, header/frame corruption, malformed
length/noise recovery, whitelist privacy and bounded inventory/event retention,
plus the five existing Movie pairing tests.

APK: app/build/outputs/apk/debug/app-debug.apk, development version 0.0.6 (6).
Build log: build/osmo-diagnostics-final-build.log. Signature log:
build/osmo-diagnostics-signature.log. No OM6 or Galaxy S23 Ultra was exercised by
Codex. No emulator, camera application or Bluetooth capture was launched.
Physical permissions, GATT operations, notification subscription, clean hardware
disconnect and normal camera return require the operator test below.

## First physical test and next experiment

1. Install the signed 0.0.6 diagnostic APK as an update. Stop WebCam streaming.
2. Power on the OM6 and keep it nearby. Close DJI Mimo so it does not hold BLE.
   Do not update firmware or reset pairing as part of this test.
3. Open Gimbal BLE diagnostics, tap Scan BLE and grant Nearby devices access.
   On older Android, allow the requested foreground location access/services.
4. Select the visible Osmo candidate and wait for the GATT inventory. Record the
   exact status if connection fails. If Android prompts for normal device pairing,
   confirm only that it is the intended physical gimbal; no bypass is provided.
5. If FFF4 actually appears as notify-capable, subscribe to it. Otherwise record
   the actual inventory and stop for review rather than guessing a control channel.
6. Observe for 15-30 seconds without requesting any motor movement. An optional
   ordinary hardware button press can help identify notification events, but no
   event meaning is assumed. Export redacted log, then Disconnect/Close diagnostics.
7. Return the JSON and note Android version, OM6 firmware (from normal DJI UI if
   readily available), observed status and whether notification counts increased.
   Do not include device serials, BLE addresses or pairing codes. Export before
   leaving the screen; unsaved logs do not survive activity recreation/app exit.

Stop here for hardware evidence. The next safe experiment is to compare actual
OM6 services and passive notification headers with the references, while proving
normal disconnection/reconnection. Only after that review should a new order
consider a documented non-actuating status exchange and any required standard
bonding/session initialization. Motor control requires separate authorization,
verified bounded parameters, stop behavior and physical clearance; none is included.

## Source and license reference table

| Ref | Source and inspected evidence | Scope and reuse |
| --- | --- | --- |
| R1 | Initial Drive interoperability research | Read first; preserved; desk-research starting point |
| R2 | alkersan/om-research at b27c9230166346300af24cda95e487de69fddf5a; README, duml.py, gimbal.py, rotate.py, LICENSE | MIT. Older-model evidence and sample fixture; notice retained in docs/third-party/om-research-LICENSE.txt. Parser is independent bitwise code. |
| R3 | malt4010/dji-gimbal-ble at 4cd27d2767cabf68fe9dc9bed2c761dca1fce80b; DJI_PROTOCOL.md and test_connect.py | OM SE only; unverified/conflicting framing and payload assumptions. No repository license found; no source copied into the app. |
| R4 | DJI Mobile SDK Android V5 issue 714, developer response February 11 2026 | Official statement: Osmo Mobile unsupported; no SDK-based OM6 control path established |
| R5 | DJI OM6 download center | Official manuals/Mimo reference; no public BLE command contract identified there |
| R6 | Android Bluetooth permissions | Platform guidance for SDK-dependent scan/connect permissions |
| R7 | Android BLE data transfer | Platform GATT discovery, callbacks and standard subscription guidance |
| R8 | o-gs/dji-firmware-tools comm_dat2pcap.py | Independent checksum/framing cross-check only; GPLv3+ reference, no implementation copied |

[R1](https://docs.google.com/document/d/1KHCeIwhz40Hv5EnN7bsqTlVBMslxTr6f/edit)
[R2](https://github.com/alkersan/om-research/tree/b27c9230166346300af24cda95e487de69fddf5a)
[R3](https://github.com/malt4010/dji-gimbal-ble/tree/4cd27d2767cabf68fe9dc9bed2c761dca1fce80b)
[R4](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/714)
[R5](https://www.dji.com/downloads/products/osmo-mobile-6)
[R6](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
[R7](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)
[R8](https://github.com/o-gs/dji-firmware-tools/blob/master/comm_dat2pcap.py)

No push, merge, motor-control implementation or unrelated repository changes.
Report added to the existing Osmo research folder; original research is preserved.
PDF and editable Markdown are provided because no DOCX renderer is installed here.

APK SHA256: 1c5eade7f6d8c3b27e4925ff7ae33180998a51e6e5f955d4b657cbbbe09c00a4
