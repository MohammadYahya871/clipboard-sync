# Clipboard Sync Protocol

## Transport

- Primary: TLS WebSocket over LAN
- Message encoding: UTF-8 JSON control frames
- Image payload: chunked base64 payloads inside `transfer_chunk` messages for the MVP

The base64 chunk approach keeps both implementations straightforward and portable for the MVP. A binary-frame upgrade path is reserved for a later phase if image throughput becomes a bottleneck.

## Message Types

- `hello`
- `auth_challenge`
- `auth_response`
- `peer_status`
- `clipboard_offer`
- `transfer_begin`
- `transfer_chunk`
- `transfer_complete`
- `clipboard_ack`
- `clipboard_reject`
- `ping`
- `pong`

## Clipboard Event

```json
{
  "eventId": "0195f067-b6cf-7a70-a2f8-1f0cc0d9f7aa",
  "sourceDeviceId": "win-desktop-01",
  "originatedAtUtc": "2026-04-15T01:02:03.123Z",
  "contentType": "IMAGE",
  "mimeType": "image/png",
  "payloadSizeBytes": 183274,
  "contentHashSha256": "04d8d2395862d6bdc8d1f4b8779e2e18f8cf4d70a340c63484726d059f291089",
  "dedupeKey": "win-desktop-01:04d8d2395862d6bdc8d1f4b8779e2e18f8cf4d70a340c63484726d059f291089",
  "transferState": "SENDING",
  "textPayload": null,
  "image": {
    "width": 1200,
    "height": 630,
    "byteSize": 183274,
    "checksumSha256": "04d8d2395862d6bdc8d1f4b8779e2e18f8cf4d70a340c63484726d059f291089",
    "encoding": "png",
    "transferId": "0195f067-b6cf-7f11-9ce0-91d708672fed"
  }
}
```

## Session Flow

1. Client opens TLS WebSocket.
2. Client sends `hello`.
3. Server replies with `auth_challenge`.
4. Client sends `auth_response` containing an HMAC proof using the pairing code.
5. Server emits `peer_status` with the accepted transport and peer metadata.

## Clipboard Delivery Flow

### Text / URL

1. Sender emits `clipboard_offer` with inline text payload.
2. Receiver applies or defers the clip.
3. Receiver responds with `clipboard_ack`.

### Image

1. Sender emits `clipboard_offer` with image metadata only.
2. Sender emits `transfer_begin`.
3. Sender emits `transfer_chunk` frames with indexed base64 chunks.
4. Sender emits `transfer_complete`.
5. Receiver validates checksum, applies the image clip, and returns `clipboard_ack`.

## Retry Policy

- `clipboard_offer` is retried if no ack arrives before timeout
- image transfers retry from chunk `0` in the MVP
- after repeated failures, the event is marked `FAILED` and surfaced in diagnostics

## Guardrails

- Text soft limit: `256 KiB`
- Image soft limit: `5 MiB`
- Image hard limit: `12 MiB`
- Oversized payloads are rejected with a user-visible diagnostic entry

