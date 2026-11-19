# WebCam 0.0.12 split candidate

This WebCam-only candidate continues the existing Android application from
canonical master `d24e4ad5e1208bb96adf430f9105baac739230f2`. It retains the
accepted automatic invitation connection and bounded endpoint failover from
`f6d95a1`, plus reconnect/resource cleanup and foreground permission guards from
`30e7789`. Camera/audio resources finish disposing before another connection
can start. Permission results received in the background cannot start capture.

The package/application ID (`com.lagdaemon.creationremote`), Djehuti WebCam
launcher, WebCam permissions, LAN-address display, and signing hooks are retained
from master. Local defaults are version code 12 and version name 0.0.12; existing
release-workflow property overrides are unchanged. Osmo/BLE diagnostics, their
permissions, UI, report storage/sharing, and research material are excluded.
Compared with accepted mixed `6ebe920`, the only WebCam source/test difference
is omission of the diagnostic callbacks and two buttons in `WebCamApp`.

The phone supplies one camera input and an optional microphone input. Movie's
capture model records enabled inputs independently on a synchronized timeline;
the Central Viewer chooses what the operator monitors. This Android split alone
does not establish desktop recording or end-to-end production acceptance.

## Candidate validation

The split candidate passed clean Debug assembly and all 5 JVM tests (2 suites),
with zero failures, errors or skips. APK metadata verified version 0.0.12 (12),
the retained package/launcher, and the existing development certificate. The
candidate PR records its fresh APK digest and full signature metadata.

Build a fresh local Debug APK and run the complete JVM suite with existing
development-drive toolchains/caches:

    gradlew --offline --no-daemon --max-workers=1 clean assembleDebug testDebugUnitTest

The candidate APK is `app/build/outputs/apk/debug/app-debug.apk`. Record its
test count, package/version, signature verification, certificate digest and APK
digest in the candidate PR. These are fresh candidate results, separate from
the historical mixed build below. Offline checks do not install or launch the
app and do not use a phone, camera, microphone, Bluetooth or Movie receiver.
No upload, release or replacement artifact is part of this split.

## Historical mixed 0.0.12 evidence

The following evidence belongs only to mixed develop commit
`6ebe9206e9dbb299470379c2f5c53e18e12ee01c`, which also contained diagnostics.
That build passed Debug assembly, 63 JVM tests with zero failures/errors, and
signing verification with the existing development certificate. Its test count
and bytes are not candidate validation and do not imply that this split includes
those diagnostics or produces a byte-identical APK.

- [Historical signed test APK](https://drive.google.com/file/d/1bltxk7SG5AP13iDuA0C_CyFKeOfodn2Y/view?usp=drivesdk)
- Version: 0.0.12 (12).
- Size: 78,287,115 bytes.
- SHA-256: `6c799cd0349158678897fb28f52460615c2b2a76ecb0268b6c4bc7c787bbc8b8`.
- Certificate SHA-256: `b4ac5bd2dd5a236a12cb0d8cc4a01284979fc5856ddde4658dee414711be869d`.

The historical link remains historical; a fresh local candidate build does not
replace it. Neither artifact is a published store release.

## Physical acceptance still required

When the Executive Director authorizes hardware testing, verify a fresh Movie
QR invitation, requested permissions, desktop video/audio reception, front/rear
switching, microphone mute, Stop, background cleanup and fresh-QR reconnect.
Use Movie's Add input control to enable phone tracks and confirm that changing
the monitored source leaves every enabled recording uninterrupted. None of
these physical or desktop acceptance steps is claimed by offline validation.
