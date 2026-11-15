# Fully automated Osmo diagnostic — 0.0.11

Permanent destination: Google Drive → Djehuti Movie → Osmo research.
https://drive.google.com/drive/folders/1jWXe3bDu1A-xFa1Bz8W_bVvXVukjRo8g

## Normal use

Install 0.0.11 over 0.0.10, preserving the same package/debug signing identity.
The main app screen now exposes RUN OSMO DIAGNOSTIC directly; that press opens the result/progress screen and starts the run. View Last Osmo Report reopens the saved result without starting Bluetooth.
Keep the Osmo powered on, stationary and disconnected from the existing phone
Bluetooth session, as in the successful scan. On the main screen press
**RUN OSMO DIAGNOSTIC**. Allow any normal Android permission/pairing request.
Read the final result. No Scan, Connect, Discover, read-checkbox or export step is
required to complete the run or preserve its local report. **Cancel Diagnostic**
is available while Bluetooth operations run. **Close** ends an active run safely.

One Run explicitly authorizes the whole read-only sequence, including the three
previously approved standard-read pairs. Scanning captures all Android-delivered
advertisements before filtering. A confident candidate requires connectable,
OM6 model-name hint AND FFF0 evidence. A single confident candidate is observed
for a 3-second settling interval, then scanning stops before connecting. Multiple
confident candidates stop the scan and require one explained selection. Device
names are shown locally for that exception only; exports retain aliases/hints.
Uncertain name/service-only devices are not connected automatically.

Connection automatically triggers service discovery immediately. The existing
inspection allowlist remains: 1800/2A01 Appearance, 180F/2A19 Battery Level,
1812/2A4A HID Information, only with READ property and fixed format validation.
There are no writes, descriptor operations, notification enables, proprietary
commands, bond changes, tracking or motor control. The Android adapter enforces
its own allowlist. All existing read/connection timeouts and cleanup stay in use.
No persistent Bluetooth service or background reconnection is introduced.

## Reports are saved without an export step

Each run has a UUID-named JSON file in the application's private files directory,
not its disposable cache. Checkpoints precede stage transitions; the final report
replaces the same run file using flush/sync and same-directory atomic replacement
where supported. Different run files retain local history. Startup restores the
latest report; an unfinished checkpoint is labeled interrupted, not successful.
A forced process kill can preserve only the last checkpoint, not unwritten radio
callbacks. App uninstall/clear-data removes these private files.

A final result is displayed only after the local save completes or explicitly
fails/times out. It separately shows Device found / Connected / Services examined /
Report saved on phone. Storage failure never reports Saved: Yes. The combined
schema `djehuti-automatic-osmo-diagnostic`, version 1, includes session/timestamps,
progress, outcome, privacy-safe candidate evidence and nested existing discovery
and service-inspection reports. Local names, addresses and pairing material are
excluded. An interrupted report is still generated and saved. Read/cleanup status
remains explicit in the nested inspection report.

## Save Report to Osmo Research

Local preservation is automatic and needs no authorization. To place a copy in
Drive, press **Save Report to Osmo Research** once after a run. Android's standard
Create Document picker opens. Choose **Google Drive → Djehuti Movie → Osmo
research**, keep `Djehuti-Osmo-Latest-Diagnostic.json`, and confirm saving.

Where the provider grants persistable WRITE access, the app remembers that one
file URI with Android's supported persistable permission mechanism. Subsequent
reports automatically update THAT SAME latest-report document; each run's full
local history remains separate. This does not grant broad Drive access or access
to a folder/account. It does not silently create extra Drive locations. If the
provider does not grant reusable access, the first copy still works when allowed
and the app explains that the destination must be chosen again. **Choose Report
Destination** changes/re-authorizes the file. Revoked/deleted/moved destinations
produce a copy failure without losing local reports.

Folder-tree grants were investigated: Android supports them for providers that
implement directory access, but support varies. A persistable single-document
URI avoids requiring Drive folder-tree support or adding a Google OAuth client.
This is a latest-document mirror, not automatic creation of a new Drive file per
run. Provider writes have a separate worker and 15-second cancellation/timeout;
local reporting is independent. A successful provider write is NOT verification
of server/cloud synchronization. Actual Google Drive persistence and subsequent
updates are **not physically verified** and must not be reported as proven.

## Lifecycle and verification limits

The coordinator has explicit checking/searching/selection/connection/examination/
collection/disconnection/saving/result states and allows one run at a time. It
uses a 60-second permission wait, 25-second scan watchdog over the scanner's
20-second scan, 60-second exceptional selection wait, 20-second connection
watchdog, 65-second examination / 45-second collection watchdogs, 5-second
cleanup watchdog and 15-second local-save watchdog. Native inspection retains
its tighter 15-second connection, 10-second discovery, 5-second read (max 8),
90-second whole-inspection and 3-second disconnect-ack bounds. No manual discovery
window is exposed. Ordinary backgrounding/cancellation stops radio operations;
necessary permission or observed OS bonding confirmation is allowed within the
existing bounded deadlines. View disposal still cancels. Pending local writes
are allowed to finish when closing; external provider I/O is canceled.

No camera, recording, WebRTC or Movie integration implementation changed. Existing
Bluetooth framework and standard-read restrictions are reused. Physical radio,
permission/pairing screens, camera return and provider behavior remain unverified.
No application or emulator is launched for automated validation.

Simulated tests cover full sequencing, not-found, ambiguity, permission rejection,
connection/discovery failures, deadlines, cancellation/interruption, safe evidence
capture, one-run exclusion, local persistence/recovery and save failure reporting.
Existing scanner, service, parser, privacy and Movie pairing checks also run.
No push/merge is authorized.

References:
https://developer.android.com/training/data-storage/shared/documents-files
https://developer.android.com/guide/topics/providers/document-provider

Final verification: Debug assembleDebug + testDebugUnitTest passed; 63 JVM tests,
zero failures/errors. APK signature verification passed with the retained debug
certificate. APK SHA-256: 5EDDD90C47C4641A846CA6EF9CB957DE6D986514BBC719F30A0C7DB2D878904B
Logs: build/automatic-osmo-011-final-build.log and build/automatic-osmo-011-signature.log.

