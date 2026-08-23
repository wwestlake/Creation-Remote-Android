# Creation Remote (Android)

The phone half of **Creation Remote** (Creation-Suite issue [#65](https://github.com/wwestlake/Creation-Suite/issues/65)): capture photos, video, or audio in the field and send them directly to a paired Creation Suite desktop install, landing in a specific project so processing can start while you're still out shooting.

Native Android: Kotlin, Jetpack Compose, CameraX (capture), WorkManager (background upload + offline queue). Ships as a **signed, sideloaded APK — no Play Store, ever.**

## Transport

No captured media ever passes through or is stored on a server. lagdaemon.com's only role is authentication and WebRTC signaling (helping this app and a paired [Creation Remote Receiver](https://github.com/wwestlake/Creation-Remote-Receiver) negotiate a direct connection). If a direct P2P connection can't be established, the file queues locally on-device (WorkManager) and retries automatically on any connectivity/reachability change — no relay, no TURN, no cloud storage fallback.

See `docs/architecture/Creation-Remote-Protocol.md` in the [Creation-Suite](https://github.com/wwestlake/Creation-Suite) repo for the wire contract this app implements against.

## Status

Scaffold only — pairing, project listing, WebRTC signaling, and the capture-to-send pipeline are not implemented yet. The screen flow (pair → device list → project picker → session metadata → capture) is navigable with stub screens.

Tracked as [CR-M5](https://github.com/wwestlake/Creation-Suite/issues/84) on the Creation Suite Road Map.

## Build

```bash
./gradlew assembleDebug
```

Requires Android SDK (`compileSdk 35`, `minSdk 26`) and JDK 17.
