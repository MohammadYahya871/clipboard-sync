#!/usr/bin/env python3
"""Simulate an Android phone sending a screenshot IMAGE over the LAN protocol."""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import ssl
import time
import uuid
from pathlib import Path

try:
    import websockets
except ImportError:
    raise SystemExit("pip install websockets  (needed for simulate_phone_image.py)")


def uuid_v7() -> str:
    ts = int(time.time() * 1000)
    rb = uuid.uuid4().bytes
    b = bytearray(16)
    b[0] = (ts >> 40) & 0xFF
    b[1] = (ts >> 32) & 0xFF
    b[2] = (ts >> 24) & 0xFF
    b[3] = (ts >> 16) & 0xFF
    b[4] = (ts >> 8) & 0xFF
    b[5] = ts & 0xFF
    b[6:] = rb[6:]
    b[6] = (b[6] & 0x0F) | 0x70
    b[8] = (b[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(b)))


def hmac_sha256_b64(secret: str, message: str) -> str:
    import hmac

    digest = hmac.new(secret.encode(), message.encode(), hashlib.sha256).digest()
    return base64.b64encode(digest).decode()


def load_settings() -> dict:
    path = Path.home() / ".local/share/ClipboardSync/settings.json"
    return json.loads(path.read_text())


async def recv_json(ws):
    raw = await asyncio.wait_for(ws.recv(), timeout=60)
    return json.loads(raw)


async def send_json(ws, payload: dict):
    await ws.send(json.dumps(payload, separators=(",", ":")))


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--png", default="/tmp/phone-sim.png")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--device-id", default="019f75sim-0000-7000-8000-000000phone1")
    args = parser.parse_args()

    settings = load_settings()
    port = settings["port"]
    pairing_code = settings["pairingCode"]
    png = Path(args.png).read_bytes()
    if png[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"Not a PNG: {args.png}")

    checksum = hashlib.sha256(png).hexdigest()
    # PNG IHDR width/height
    width = int.from_bytes(png[16:20], "big")
    height = int.from_bytes(png[20:24], "big")
    event_id = uuid_v7()
    transfer_id = uuid_v7()
    device_id = args.device_id

    ssl_ctx = ssl.create_default_context()
    ssl_ctx.check_hostname = False
    ssl_ctx.verify_mode = ssl.CERT_NONE

    uri = f"wss://{args.host}:{port}/ws"
    print(f"Connecting {uri} with {len(png)} byte PNG ({width}x{height})")

    async with websockets.connect(uri, ssl=ssl_ctx, max_size=16 * 1024 * 1024) as ws:
        await send_json(
            ws,
            {
                "type": "hello",
                "timestampUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "deviceId": device_id,
                "sessionId": uuid_v7(),
            },
        )
        challenge_env = await recv_json(ws)
        assert challenge_env["type"] == "auth_challenge", challenge_env
        session_id = challenge_env["sessionId"]
        challenge = challenge_env["challenge"]
        response = hmac_sha256_b64(pairing_code, f"{challenge}:{session_id}:{device_id}")
        await send_json(
            ws,
            {
                "type": "auth_response",
                "timestampUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "sessionId": session_id,
                "deviceId": device_id,
                "response": response,
            },
        )
        status = await recv_json(ws)
        print("auth:", status.get("type"), status.get("status"))

        event = {
            "eventId": event_id,
            "sourceDeviceId": device_id,
            "originatedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "contentType": "IMAGE",
            "mimeType": "image/png",
            "payloadSizeBytes": len(png),
            "contentHashSha256": checksum,
            "dedupeKey": f"{device_id}:{checksum}",
            "transferState": "QUEUED",
            "image": {
                "width": width,
                "height": height,
                "byteSize": len(png),
                "checksumSha256": checksum,
                "encoding": "png",
                "transferId": transfer_id,
            },
        }
        await send_json(
            ws,
            {
                "type": "clipboard_offer",
                "timestampUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "event": event,
            },
        )

        chunk_size = 32 * 1024
        total_chunks = (len(png) + chunk_size - 1) // chunk_size
        descriptor = {
            "transferId": transfer_id,
            "eventId": event_id,
            "totalChunks": total_chunks,
            "totalBytes": len(png),
            "checksumSha256": checksum,
        }
        await send_json(
            ws,
            {
                "type": "transfer_begin",
                "timestampUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "transfer": descriptor,
            },
        )
        for index in range(total_chunks):
            start = index * chunk_size
            end = min(len(png), start + chunk_size)
            await send_json(
                ws,
                {
                    "type": "transfer_chunk",
                    "timestampUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "chunk": {
                        "transferId": transfer_id,
                        "chunkIndex": index,
                        "base64Payload": base64.b64encode(png[start:end]).decode(),
                    },
                },
            )
        t0 = time.time()
        await send_json(
            ws,
            {
                "type": "transfer_complete",
                "timestampUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "transfer": descriptor,
            },
        )

        while True:
            env = await recv_json(ws)
            print("recv:", env.get("type"), env.get("status") or env.get("reason"))
            if env.get("type") in ("clipboard_ack", "clipboard_reject"):
                elapsed = time.time() - t0
                print(f"apply round-trip {elapsed:.2f}s")
                return 0 if env.get("type") == "clipboard_ack" else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
