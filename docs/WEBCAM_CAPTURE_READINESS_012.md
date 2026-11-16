# WebCam 0.0.12 capture test build

This continues the existing Android application and preserves its package,
signing identity and Osmo diagnostics. Camera/audio resources finish disposing
before another connection can start. Permission results received while the app
is backgrounded cannot start capture.

The phone remains one camera input plus an optional microphone input. Movie
records these as independent synchronized tracks alongside other enabled inputs;
its Central Viewer only chooses what the operator monitors.

Debug build and all 63 JVM tests passed, with zero failures/errors. APK signing
verification passed using the existing development certificate. Version code: 12.

- [Download the signed test APK](https://drive.google.com/file/d/1bltxk7SG5AP13iDuA0C_CyFKeOfodn2Y/view?usp=drivesdk)
- Delivery folder: existing Djehuti Movie / Osmo research.
- Size: 78,287,115 bytes.
- SHA-256: `6c799cd0349158678897fb28f52460615c2b2a76ecb0268b6c4bc7c787bbc8b8`.
- Certificate SHA-256: `b4ac5bd2dd5a236a12cb0d8cc4a01284979fc5856ddde4658dee414711be869d`.

Install over the existing WebCam app, open it, scan a fresh Movie invitation and
grant camera/microphone access as requested. Use Movie's Add input control to
enable phone video and audio as recording tracks. Switching the desktop viewer
must not stop the phone stream. Camera switching, microphone mute, Stop,
background cleanup and a fresh-QR reconnect need the physical phone operator.
This is a signed test artifact, not a published store release.
