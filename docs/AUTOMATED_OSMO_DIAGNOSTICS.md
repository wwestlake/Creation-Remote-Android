# Automated Osmo Diagnostics (Work Order 2)

## Operator workflow

Install development APK 0.0.7 (7) over 0.0.6. This retains the existing package and
Android debug signing identity; it does not change release signing or security
scanner behavior. Stop WebCam streaming, power on the gimbal, and close DJI Mimo.
Open **Osmo Diagnostics**, then press **Start Diagnostics** and grant Android's
Nearby devices permission (Location on Android 8–11).

The app searches for 15 seconds, connects when there is one clear advertised OM6 model-name candidate, examines services, enables recognized notification channels,
observes for 30 seconds, disconnects, and prepares a JSON report. If multiple
candidates or only uncertain advertising evidence are present, it asks which
nearby device belongs to the operator. It never chooses the first/strongest radio.
A Bluetooth service alone is not reliable identification. Names can also be
spoofed: automatic selection is a practical candidate heuristic, not proof of model
identity or authenticated device ownership.

Press **Share Diagnostic Report** and select Drive, email, or another installed
sharing app. No manual file navigation or protocol decisions are needed. The
report remains available after success, failure, cancellation, and activity
recreation within the running app. Android process termination can discard it;
share before exiting the app. Backgrounding or closing diagnostics cancels any
active radio session. Permission prompts are exempt from background cancellation
and have a bounded wait. Returning to diagnostics retains the completed report.

## Implementation and bounds

- `OsmoDiagnosticRun`: deterministic state machine, injected monotonic clock;
  permission 60 seconds, scan 15, selection 60, connection 15, discovery 10,
  subscription 10 per channel, monitoring 30, and entire run 180 seconds.
  Duplicate starts and out-of-phase events are ignored.
- `BleDiagnostics`: existing scanner/GATT transport, serialized main-thread
  callbacks, generation guards, sequential subscriptions and resource disposal.
  Scanning considers advertised Osmo/OM model names or FFF0 as a weak hint, retains
  at most 100 candidate devices, and does not log addresses or names. Unknown
  services are recorded in full, not treated as incompatibility. All services and
  characteristics returned by Android are inventoried, including duplicate UUID
  instances. No old 64-service / 256-characteristic truncation remains.
- Automatic subscription is limited to FFF4 under FFF0 with notify/indicate
  properties. Only standard CCCD 2902 enable writes are made. Other channels are
  recorded for engineering review, never guessed. No initialization, command,
  characteristic read/write, bonding bypass, or motor control is present.
- `DumlParser`: existing receive-only parser, now with distinct invalid-header,
  header-checksum, frame-checksum and discarded-byte counters. Notifications and
  bytes are counted once per callback, independently of the number of parsed
  frames. Fragmented frames retain per-channel parser state until disconnection.
- `BleJournal` schema/diagnostic version 2: app/Android versions, capabilities,
  UTC session timestamps, monotonic event/duration times, privacy-safe aliases
  and model/name/service identification hints, complete service/characteristic inventory, connection
  and subscription results, notification/byte totals, validated header events,
  parser totals, errors, local-close observations, outcome and unresolved questions.
  Event detail is capped at 1,000 entries with an explicit dropped count; complete
  inventory, subscription results and aggregate counters survive rollover.
- Report preparation follows resource cleanup. Cancellation/errors retain all
  previously collected evidence. Local close is recorded separately from a remote
  disconnection; asynchronous close acknowledgement is not claimed.
- `OsmoDiagnosticsModel` retains terminal reports through activity recreation.
  `BleDiagnosticsScreen` shows progress, a plain-English outcome, Start/Cancel,
  ambiguity-only device choice and Share. Technical JSON is optional.
- Sharing uses Android FileProvider content URIs and temporary read permission,
  scoped only to the diagnostic cache subdirectory. No storage permission or
  public file picker is needed. JSON omits raw payloads, advertisements, names,
  addresses, serials, pairing secrets and authentication material. Diagnostic
  aliases apply only to that session. Shared cache reports may be removed by
  Android; the recipient's copy is independent.

Existing camera/microphone/WebRTC/pairing behavior is unchanged except for the
entry-point label. No Movie, shared library, website, or production receiver code
is modified. Existing local-only diagnostic work is preserved as the explicit
82cbe9b baseline; this order uses a fresh dependent local branch and does not
integrate or publish those commits.

## Verification and limits

JVM coverage includes end-to-end state transitions, exact monitoring duration,
permission denial/timeouts, ambiguous/no candidates, connection/discovery/
subscription timeouts, selection/overall bounds, cancellation and late events,
restart, unknown channels, failed subscriptions, report rollover/privacy,
checksum categories, receive-only call restrictions and FileProvider scope.
Existing parser and Movie invitation/endpoint tests are also run. Build and test
results are recorded in the completion report and canonical build logs.

No physical OM6/S23 test or Android sharing target interaction is claimed. Actual
advertisements, Android permissions, GATT connection, notification subscriptions,
background cleanup, camera return and recipient readability need the Executive
Director's device test. No notifications means only that none were observed;
it does not establish unsupported hardware. This diagnostic cannot determine
undocumented initialization/authentication needs or command support.

Next action: install APK, run once, and share the report. Stop pending physical
evidence. No push, merge or motor-control work is authorized.

Android sharing reference: https://developer.android.com/reference/androidx/core/content/FileProvider


Local subscription statuses: -1 missing standard descriptor, -2 local subscription
request rejected, -3 confirmation timeout. Nonnegative statuses are Android GATT
callback values. Service-instance identifiers link repeated service UUIDs to
their characteristics. No device identity is inferred from these instance IDs.



Verified Debug build: assembleDebug + testDebugUnitTest passed; 24 JVM tests,
0 failures/errors. APK signature verification passed with the retained debug
certificate (SHA-256 b4ac5bd2dd5a236a12cb0d8cc4a01284979fc5856ddde4658dee414711be869d).
APK SHA-256: 35d8560921174bb0c1f6386d0e4e3857be6e723b07a5f7d4980aa70d824d51a4.
Logs: build/automatic-osmo-verified-build.log and build/automatic-osmo-signature.log.

