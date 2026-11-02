# Automatic Movie pairing — 0.0.5 development build

Click **Pair Phone** in Movie and leave its QR window open. On this app choose
**Scan pairing QR code**, scan and grant the requested camera/microphone access.
Connection attempts start automatically: there is no IP selection or extra
Connect step. Existing front/rear and microphone controls are retained. Manual
entry is an optional fallback; connection details are optional diagnostics.

The invitation supplies up to 16 automatically selected private IPv4 endpoints.
The client tries alternatives with bounded timeouts and accepts Movie only after
verifying the encrypted answer under the invitation's session/key. A single-use
invitation expires at Movie after 120 seconds; expiry/consumption rejection is
also authenticated. Old single-endpoint invitations remain supported. No account,
public STUN/TURN, cloud relay or same-looking-address requirement is introduced.

Failure messages distinguish reachability/refusal, authentication, expired/used
invitation and media failures where evidence permits. Check local reachability,
firewall/router isolation and that Pair Phone remains open; do not assume wrong
Wi-Fi. On failure the preview is released and retry controls recover. Technical
logs omit invitations, keys and SDP.

Version 0.0.5 (5) was built with assembleDebug testDebugUnitTest; five JVM tests
passed with zero failures/errors. Signing verification passed with the existing
development certificate. APK: app/build/outputs/apk/debug/app-debug.apk; SHA256:
fde983362238d9aa7d1923bf4a4e11a78fd9f2a36f7173cf913af9a9a87bdcd7.
No physical phone acceptance is claimed. Final Galaxy S23 Ultra scan, permissions,
connection and cleanup checks belong to the Executive Director. The desktop
report also records an intermittent synthetic audio timestamp regression that
must be investigated before production recording; this order does not redesign
the existing media clock.

No push, merge, release or Drive replacement was performed under this order.
