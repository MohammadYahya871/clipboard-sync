# Clipboard Sync Architecture

## Goals

- Local-first bidirectional clipboard sync between Windows and Android
- Reliable text, URL, and image synchronization
- Explicit loop prevention and deduplication
- Secure pairing without cloud relay

## Topology

- Windows hosts the primary LAN endpoint over TLS WebSocket
- Android discovers and connects to the Windows peer on the local network
- A single active transport owns delivery for a given event
- BLE is reserved for discovery, reconnect assist, and small-text fallback in a later phase

## Runtime Components

### Windows

- `ClipboardMonitor`
  - registers a hidden message window with `AddClipboardFormatListener`
  - emits normalized clipboard snapshots
- `ClipboardExtractor`
  - converts Windows clipboard formats into canonical text or PNG-backed image payloads
- `ClipboardWriter`
  - writes remote text or image content into the Windows clipboard
- `LanServer`
  - hosts an HTTPS WebSocket endpoint
- `SyncCoordinator`
  - applies loop suppression, queueing, retries, ack tracking, and conflict policy

### Android

- `ClipboardObserver`
  - listens to `ClipboardManager.OnPrimaryClipChangedListener`
  - active only while the app is visible / foreground-active
- `ClipboardNormalizer`
  - converts clipboard text or image URIs into canonical payloads
- `ClipboardApplyUseCase`
  - writes remote text or app-cached image URIs into the Android clipboard
- `LanClient`
  - maintains a TLS WebSocket session to the Windows host
- `ForegroundSyncService`
  - keeps the session alive while the user has sync enabled

## Data Flow

1. Device observes a local clipboard change.
2. Clipboard content is normalized into a canonical `ClipboardEvent`.
3. The deduper checks recent event IDs and content hashes.
4. The sync coordinator enqueues the event and chooses the active transport.
5. The receiving device validates pairing state and session auth.
6. The receiver reconstructs payloads, applies content to the local clipboard, records a suppression token, and sends an ack.
7. The sender marks the event as `ACKED` or retries on timeout.

## Loop Prevention

- Every clip produces:
  - `eventId`
  - `contentHashSha256`
  - `dedupeKey`
- The receiver records a short-lived suppression entry after applying a remote clip.
- If the next local clipboard callback yields the same hash within the suppression window, the clip is ignored instead of re-sent.
- Text hashes normalize line endings to LF before hashing to avoid Windows/Android newline bounce loops.

## Conflict Policy

- If a remote clip arrives within the conflict window and the local clipboard has changed more recently, the remote clip is stored in history instead of overwriting the current clipboard.
- The UI surfaces the deferred clip so the user can resend it manually if desired.

## Security Model

- Pairing is explicit and mutual
- Windows generates a self-signed certificate and stable device ID
- Pairing exchanges:
  - server address
  - TLS certificate fingerprint
  - temporary pairing code
  - device metadata
- Android stores trusted peer metadata and only connects when the certificate fingerprint matches
- Session auth adds a challenge-response proof derived from the pairing secret

## Image Pipeline

- Images are normalized to PNG for transfer
- Android image URIs are copied into app cache immediately
- Images move through the wire as chunked transfer messages
- End-to-end checksum validation is required before the item is applied
- Temporary image cache files are pruned on startup and size-limited

