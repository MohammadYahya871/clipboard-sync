import Foundation

enum ContentType: String, Codable, CaseIterable {
    case text = "TEXT"
    case url = "URL"
    case image = "IMAGE"
    case mixedUnsupported = "MIXED_UNSUPPORTED"
}

enum TransferState: String, Codable, CaseIterable {
    case queued = "QUEUED"
    case sending = "SENDING"
    case awaitingAck = "AWAITING_ACK"
    case acked = "ACKED"
    case failed = "FAILED"
    case deferred = "DEFERRED"
}

enum TransportKind: String, Codable, CaseIterable {
    case none = "NONE"
    case lan = "LAN"
    case bleFallback = "BLE_FALLBACK"

    var label: String {
        switch self {
        case .lan: return "Wi-Fi / LAN"
        case .bleFallback: return "Bluetooth fallback"
        case .none: return "No active transport"
        }
    }
}

struct ImageMetadata: Codable, Equatable {
    var width: Int
    var height: Int
    var byteSize: Int64
    var checksumSha256: String
    var encoding: String
    var transferId: String?
}

struct ClipboardEvent: Codable, Equatable, Identifiable {
    var id: String { eventId }

    var eventId: String
    var sourceDeviceId: String
    var originatedAtUtc: String
    var contentType: ContentType
    var mimeType: String
    var payloadSizeBytes: Int64
    var contentHashSha256: String
    var dedupeKey: String
    var transferState: TransferState
    var textPayload: String?
    var image: ImageMetadata?
}

struct TransferDescriptor: Codable, Equatable {
    var transferId: String
    var eventId: String
    var totalChunks: Int
    var totalBytes: Int64
    var checksumSha256: String
}

struct TransferChunk: Codable, Equatable {
    var transferId: String
    var chunkIndex: Int
    var base64Payload: String
}

struct ProtocolEnvelope: Codable, Equatable {
    var type: String
    var timestampUtc: String
    var sessionId: String?
    var deviceId: String?
    var challenge: String?
    var response: String?
    var status: String?
    var reason: String?
    var event: ClipboardEvent?
    var transfer: TransferDescriptor?
    var chunk: TransferChunk?

    init(
        type: String,
        timestampUtc: String = Date.utcNowString,
        sessionId: String? = nil,
        deviceId: String? = nil,
        challenge: String? = nil,
        response: String? = nil,
        status: String? = nil,
        reason: String? = nil,
        event: ClipboardEvent? = nil,
        transfer: TransferDescriptor? = nil,
        chunk: TransferChunk? = nil
    ) {
        self.type = type
        self.timestampUtc = timestampUtc
        self.sessionId = sessionId
        self.deviceId = deviceId
        self.challenge = challenge
        self.response = response
        self.status = status
        self.reason = reason
        self.event = event
        self.transfer = transfer
        self.chunk = chunk
    }
}

struct PairingPayload: Codable, Equatable, Identifiable {
    var id: String { deviceId }

    var deviceId: String
    var displayName: String
    var serviceName: String
    var host: String
    var port: Int
    var pairingCode: String
    var certificateSha256: String
}

struct DiscoveryMessage: Codable, Equatable {
    static let discoverType = "clipboard_sync_discover"
    static let responseType = "clipboard_sync_device"

    var type: String
    var deviceId: String?
    var displayName: String?
    var serviceName: String?
    var host: String?
    var port: Int?
    var certificateSha256: String?
}

struct ResolvedPeerEndpoint: Codable, Equatable {
    var serviceName: String
    var host: String
    var port: Int
}

struct NormalizedClipboard {
    var event: ClipboardEvent
    var imageBytes: Data?
    var previewText: String
    var previewUri: String?
    var fromRemote: Bool
}

enum ProtocolJSON {
    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = []
        return encoder
    }()

    static let decoder: JSONDecoder = {
        JSONDecoder()
    }()
}

extension Date {
    static var utcNowString: String {
        ISO8601DateFormatter.clipboardSync.string(from: Date())
    }
}

extension ISO8601DateFormatter {
    static let clipboardSync: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter
    }()
}

