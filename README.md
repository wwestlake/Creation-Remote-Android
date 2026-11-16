# Djehuti WebCam (Android)

The original Creation Remote Android project is repurposed as the native phone
camera/microphone client for Djehuti Movie. The separate web-based Djehuti Remote
continues field asset collection and is unaffected by this repository.

Kotlin/Jetpack Compose with Camera2/libwebrtc capture, local preview, front/rear
selection, microphone transmission control, one-use authenticated LAN pairing,
direct VP8/Opus WebRTC, and explicit stop/background cleanup. No website account,
external STUN/TURN, or cloud signaling/media relay is used.

The existing package/namespace is retained. Do not create a competing Android app.
Current webcam test build: [0.0.12 capture readiness](docs/WEBCAM_CAPTURE_READINESS_012.md).
See [automatic pairing and current test artifact](docs/AUTOMATIC_PHONE_PAIRING.md).
For the current single-button workflow, see [Fully automated Osmo diagnostic](docs/AUTOMATIC_OSMO_DIAGNOSTIC_011.md).
The previous [manual service inspection](docs/OSMO_SERVICE_INSPECTION.md) is historical.
The comprehensive scan remains available; [0.0.8 discovery diagnostics](docs/BLUETOOTH_DISCOVERY_DIAGNOSTICS.md) documents its report.
The previous [connection diagnostic](docs/AUTOMATED_OSMO_DIAGNOSTICS.md) is historical; its notification-enabling workflow is not used by the current read-only inspection.
Historical [Order 3 implementation](docs/WEBCAM_ORDER_3.md) remains available.
Actual Galaxy S23 Ultra operation must be verified; a signed APK/build alone does
not establish phone-to-Movie acceptance.

## Build

Use the installed Android SDK (compile/target 35, minimum 26), a compatible JDK,
and Gradle wrapper with development-drive caches/temp/user-home configuration.

    gradlew --no-daemon --max-workers=1 assembleDebug testDebugUnitTest

The signed development APK is app/build/outputs/apk/debug/app-debug.apk.
This is a test artifact, not a published release. No Play Store distribution is
required. Do not invoke the tagged release workflow or publish without approval.

## Build and diagnostic delivery — single location

Executive direction, November 3, 2026: use **Google Drive → Djehuti Movie →
Osmo research** for every WebCam APK, engineering report, and physical diagnostic
report. This replaces the former Djehuti WebCam Testing destination. Do not
create separate testing/integration build folders or scatter copies elsewhere.

Canonical folder: https://drive.google.com/drive/folders/1jWXe3bDu1A-xFa1Bz8W_bVvXVukjRo8g

Keep versioned APK names and upload new builds here. Verify the destination after
upload and return its actual Drive link. Existing duplicate 0.0.7 upload is named
`Djehuti-WebCam-0.0.7-original-upload.apk` to preserve both prior download links;
`Djehuti-WebCam-0.0.7-Automatic-Osmo-Diagnostics.apk` is the normal 0.0.7 artifact.
