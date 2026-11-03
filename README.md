# Djehuti WebCam (Android)

The original Creation Remote Android project is repurposed as the native phone
camera/microphone client for Djehuti Movie. The separate web-based Djehuti Remote
continues field asset collection and is unaffected by this repository.

Kotlin/Jetpack Compose with Camera2/libwebrtc capture, local preview, front/rear
selection, microphone transmission control, one-use authenticated LAN pairing,
direct VP8/Opus WebRTC, and explicit stop/background cleanup. No website account,
external STUN/TURN, or cloud signaling/media relay is used.

The existing package/namespace is retained. Do not create a competing Android app.
See [automatic pairing and current test artifact](docs/AUTOMATIC_PHONE_PAIRING.md).
For the receive-only gimbal test, see [automated Osmo Diagnostics](docs/AUTOMATED_OSMO_DIAGNOSTICS.md).
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
