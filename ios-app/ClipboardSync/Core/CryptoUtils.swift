import CryptoKit
import Foundation
import Security

enum CryptoUtils {
    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func sha256Hex(_ text: String) -> String {
        sha256Hex(Data(text.utf8))
    }

    static func hmacSha256Base64(secret: String, message: String) -> String {
        let key = SymmetricKey(data: Data(secret.utf8))
        let mac = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key)
        return Data(mac).base64EncodedString()
    }

    static func randomBase64(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
    }

    static func uuidV7() -> String {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        var bytes = [UInt8](repeating: 0, count: 16)
        bytes[0] = UInt8((now >> 40) & 0xff)
        bytes[1] = UInt8((now >> 32) & 0xff)
        bytes[2] = UInt8((now >> 24) & 0xff)
        bytes[3] = UInt8((now >> 16) & 0xff)
        bytes[4] = UInt8((now >> 8) & 0xff)
        bytes[5] = UInt8(now & 0xff)

        var random = [UInt8](repeating: 0, count: 10)
        _ = SecRandomCopyBytes(kSecRandomDefault, random.count, &random)
        bytes.replaceSubrange(6..<16, with: random)
        bytes[6] = (bytes[6] & 0x0f) | 0x70
        bytes[8] = (bytes[8] & 0x3f) | 0x80

        return String(
            format: "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5],
            bytes[6], bytes[7],
            bytes[8], bytes[9],
            bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
        )
    }
}

enum PairingCodeCodec {
    static func decode(_ encoded: String) throws -> PairingPayload {
        var normalized = encoded.trimmingCharacters(in: .whitespacesAndNewlines)
        normalized = normalized.replacingOccurrences(of: "-", with: "+")
        normalized = normalized.replacingOccurrences(of: "_", with: "/")
        while normalized.count % 4 != 0 {
            normalized.append("=")
        }
        guard let data = Data(base64Encoded: normalized) else {
            throw ClipboardSyncError.invalidPairingPayload
        }
        return try ProtocolJSON.decoder.decode(PairingPayload.self, from: data)
    }

    static func encode(_ payload: PairingPayload) throws -> String {
        let data = try ProtocolJSON.encoder.encode(payload)
        return data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

enum ClipboardSyncError: Error, LocalizedError {
    case invalidPairingPayload
    case missingTrustedPeer
    case unsupportedClipboard
    case certificateFingerprintMismatch
    case checksumMismatch

    var errorDescription: String? {
        switch self {
        case .invalidPairingPayload: return "Invalid pairing payload"
        case .missingTrustedPeer: return "No trusted Windows peer is configured"
        case .unsupportedClipboard: return "No supported clipboard item was found"
        case .certificateFingerprintMismatch: return "Windows certificate fingerprint did not match the trusted pairing record"
        case .checksumMismatch: return "Image transfer checksum did not match"
        }
    }
}

