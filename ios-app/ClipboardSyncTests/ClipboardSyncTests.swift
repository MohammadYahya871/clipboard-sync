import CryptoKit
import XCTest
@testable import ClipboardSync

final class ClipboardSyncTests: XCTestCase {
    func testPairingPayloadRoundTrip() throws {
        let payload = PairingPayload(
            deviceId: "win-desktop-01",
            displayName: "Windows PC",
            serviceName: "ClipboardSync-win-desktop-01",
            host: "192.168.1.20",
            port: 43871,
            pairingCode: "secret",
            certificateSha256: "abc123"
        )

        let encoded = try PairingCodeCodec.encode(payload)
        let decoded = try PairingCodeCodec.decode(encoded)
        XCTAssertEqual(decoded, payload)
    }

    func testProtocolEnvelopeEncoding() throws {
        let event = ClipboardEvent(
            eventId: "event-1",
            sourceDeviceId: "ios-1",
            originatedAtUtc: "2026-04-15T01:02:03.123Z",
            contentType: .text,
            mimeType: "text/plain",
            payloadSizeBytes: 5,
            contentHashSha256: CryptoUtils.sha256Hex("hello"),
            dedupeKey: "ios-1:\(CryptoUtils.sha256Hex("hello"))",
            transferState: .queued,
            textPayload: "hello",
            image: nil
        )
        let envelope = ProtocolEnvelope(type: "clipboard_offer", timestampUtc: "2026-04-15T01:02:03.123Z", event: event)
        let data = try ProtocolJSON.encoder.encode(envelope)
        let decoded = try ProtocolJSON.decoder.decode(ProtocolEnvelope.self, from: data)
        XCTAssertEqual(decoded, envelope)
    }

    func testHmacAuthResponse() {
        let actual = CryptoUtils.hmacSha256Base64(secret: "pairing-secret", message: "challenge:session:ios-1")
        let key = SymmetricKey(data: Data("pairing-secret".utf8))
        let expected = Data(HMAC<SHA256>.authenticationCode(for: Data("challenge:session:ios-1".utf8), using: key)).base64EncodedString()
        XCTAssertEqual(actual, expected)
    }

    func testLoopGuardSuppressesRemoteEcho() {
        let guardrail = LoopGuard()
        XCTAssertFalse(guardrail.shouldSuppressLocal(hash: "hash"))
        guardrail.markRemoteApplied("hash")
        XCTAssertTrue(guardrail.shouldSuppressLocal(hash: "hash"))
    }

    func testChecksumMismatchCanBeDetected() {
        let bytes = Data("image-bytes".utf8)
        XCTAssertEqual(CryptoUtils.sha256Hex(bytes), CryptoUtils.sha256Hex(bytes))
        XCTAssertNotEqual(CryptoUtils.sha256Hex(bytes), CryptoUtils.sha256Hex(Data("other".utf8)))
    }
}

