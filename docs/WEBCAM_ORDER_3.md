# Djehuti WebCam — Order 3 native client

This repurposes the original Remote Android project. Package/namespace remain
`com.lagdaemon.creationremote` so this is the existing application, not a competing
Android app. The visible launcher/product name is Djehuti WebCam. The separate
web-based Remote is not modified.

## Reuse and replacement

Reuse Kotlin/Compose, the application/theme, CameraX/ML Kit invitation scanning,
permission/lifecycle APIs, OkHttp and Stream's native WebRTC distribution.
Replace website OAuth/PKCE, website device/project selection, session metadata,
field-upload/data-channel transfer, and their obsolete screens. One Camera2
WebRTC capturer supplies local preview and live transmission; the QR camera is
released before streaming starts. The native audio device module supplies Opus.

The retained `io.getstream:stream-webrtc-android:1.3.9` is the version currently
documented by its upstream README and available from Maven Central. Its AAR and
POM were resolved during the build. The POM declares Apache-2.0; upstream WebRTC
and bundled component notices also apply. No hosted Stream SDK/service is used.
This is not a claim that a vulnerability audit has certified the binary. Maintain
an explicit dependency update/security review process; do not use snapshots.

## Pairing, transport, timing

Scan or paste the full JSON invitation from Movie or the isolated Order 1 endpoint.
Only private IPv4 `/signal` endpoints are accepted, without query credentials,
userinfo or fragments. Invitations use the same encrypted HKDF/AES-GCM offer and
answer protocol as Order 1. The secret is not sent over the LAN socket. Media is
DTLS/SRTP; both peers have no external ICE servers. VP8 and Opus are explicitly
selected from the native sender capabilities. No cloud account or server is used.

The app requests 1280x720/30 fps; actual device output must be verified. Front/rear
selection and camera switching use the same capturer. A microphone opened for a
session can have transmission muted/unmuted; enabling a microphone that was not
opened requires Stop and a fresh connection. Stop/background/failed connection
disposes camera, audio, peer, socket, preview/EGL and worker resources. Reconnect
uses a newly issued one-use invitation; it does not reuse consumed credentials.

The authenticated `webcam-timing-v1` data channel answers host clock probes with
phone monotonic receive/send timestamps, paired wall time and latest native camera
timestamp, all as decimal integer strings. Native RTP/RTCP timing remains intact.
Movie's `docs/WEBCAM_TIMING_CONTRACT.md` governs host mapping and recording readiness.
Do not represent transport clock uncertainty as measured physical capture accuracy.

## Build and signing

Use the existing Gradle project and Android SDK, a supported installed JDK, one
Gradle worker, and development-drive locations for Gradle home, Android user home,
JVM user home and temporary files. Build `assembleDebug testDebugUnitTest`.
The test artifact is `app/build/outputs/apk/debug/app-debug.apk`, signed with the
development certificate. It is a sideload test artifact, not a published release.
The existing release-key workflow is not invoked or changed by this order.

## Exact Galaxy S23 Ultra procedure (requires physical device)

1. Connect the phone and Windows PC to the same non-isolated LAN/Wi-Fi. Keep the
   phone app visible. Do not disable security settings or certificate validation.
2. Transfer the signed APK locally (USB/file transfer), or, with USB debugging
   explicitly enabled and the PC authorized on the phone, run
   `adb -s <phone-serial> install -r <absolute-path-to-app-debug.apk>`.
   If a prior package has a different signing certificate, stop and resolve that
   deliberately; do not silently uninstall an existing application or its data.
3. Start Movie's isolated endpoint using the Movie harness's Python dependencies:
   `python tests/webcam-lan/endpoint.py --bind <PC-LAN-IPv4> --invitation <private-json> --results <counts-json> --qr <private-svg>`.
   All paths must be explicit development-drive paths. The listener uses port 8765.
4. Open the private SVG on the PC and scan it in WebCam, or privately paste the
   invitation JSON. It expires after 120 seconds and works once. Do not upload it
   to a website, issue, log, or cloud QR generator.
5. Select rear/front, enable microphone, then Connect and stream. Grant camera and
   microphone permission on the phone. Verify local preview and connected status.
   Confirm both videoFrames and audioSamples grow in the endpoint's counts JSON.
6. Switch cameras, toggle microphone transmission, Stop, and inspect the phone's
   camera/microphone indicators. Verify resources are released. Backgrounding must
   stop capture. Capture actual errors if a permission is denied.
7. Restart the isolated endpoint to issue a new invitation. Reconnect. Separately
   try the consumed invitation and confirm rejection. Repeat after Wi-Fi loss.
   The isolated endpoint intentionally bounds a session to 90 seconds; sustained
   operation measurements belong to Order 4 using the production receiver.
8. For Movie integration, use Track Inspector's Pair/disconnect control, transfer
   its private invitation, then select WebCam video under Camera and WebCam audio
   under Microphone on already-existing tracks. Only explicitly armed tracks
   record. Wait for timing readiness; do not infer synchronization from preview.

Physical evidence must include phone/Android version, APK SHA-256 and signing
fingerprint, connection state, observed media, camera switching, cleanup and
fresh-invitation reconnect. Synthetic endpoint tests are not phone evidence.

## Validation state

Physical Galaxy S23 Ultra testing has not been performed: ADB currently lists only
an offline emulator. Debug `assembleDebug testDebugUnitTest` passed, including both
invitation/cryptography tests (zero failures/errors). `apksigner verify --print-certs`
passed. The APK is 78,138,892 bytes; SHA-256:
`ae837f57c7c346c5e450fd5f00be9ea71afa3ae74b7b6f430a839ed39a3e2879`.
Development signing certificate SHA-256:
`b4ac5bd2dd5a236a12cb0d8cc4a01284979fc5856ddde4658dee414711be869d`.
The signed APK is delivered locally at the build path above, not published as a
release or committed binary. No claim of physical camera/microphone operation is
made. Order 4 must characterize sustained drift,
physical synchronization, network interruption and lifecycle/device behavior.
