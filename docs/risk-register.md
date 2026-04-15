# Risk Register

## Android background clipboard access

- Risk: Android restricts clipboard reads for apps that are not the default IME or in focus.
- Mitigation: outbound Android clipboard capture only runs while the app is visible / foreground-active. The app documents this clearly.

## Android OEM battery policies

- Risk: foreground services and sockets may still be interrupted on aggressive OEM builds.
- Mitigation: foreground notification, battery-optimization guidance, reconnect backoff, user-visible status.

## Image URI expiration

- Risk: copied images may be exposed as transient `content://` URIs.
- Mitigation: normalize and cache immediately when observed.

## Self-signed LAN TLS friction

- Risk: certificate trust is manual unless the client pins the certificate fingerprint from pairing.
- Mitigation: pin the Windows certificate fingerprint during pairing and validate it on every connection.

## Large payload transfer overhead

- Risk: JSON + base64 chunking has overhead.
- Mitigation: enforce payload caps, keep chunk sizes reasonable, and reserve binary frame optimization for a later phase.

## BLE reliability for rich payloads

- Risk: BLE is a poor default path for large images.
- Mitigation: BLE remains control-plane-first; large payloads queue until LAN returns.

