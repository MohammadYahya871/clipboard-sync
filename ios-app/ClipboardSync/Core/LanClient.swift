import Combine
import CryptoKit
import Foundation
import Security

enum LanConnectionState: String, Codable {
    case disconnected = "DISCONNECTED"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case ready = "READY"
    case failed = "FAILED"
}

@MainActor
final class LanClient: ObservableObject {
    @Published private(set) var state: LanConnectionState = .disconnected

    var onEnvelope: ((ProtocolEnvelope) -> Void)?
    private let logger: AppLogger
    private var session: URLSession?
    private var webSocket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?

    init(logger: AppLogger) {
        self.logger = logger
    }

    func connect(peer: TrustedPeer, localDeviceId: String) {
        disconnect()
        state = .connecting

        let delegate = CertificatePinningDelegate(expectedSha256: peer.certificateSha256) { [weak self] message in
            Task { @MainActor in self?.logger.warn(message) }
        }
        let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
        guard let url = URL(string: "wss://\(peer.host):\(peer.port)/ws") else {
            state = .failed
            return
        }

        let task = session.webSocketTask(with: url)
        self.session = session
        self.webSocket = task
        task.resume()
        state = .connected
        logger.info("LAN WebSocket opening to \(peer.host):\(peer.port)")
        sendEnvelope(ProtocolEnvelope(type: "hello", sessionId: CryptoUtils.uuidV7(), deviceId: localDeviceId))
        receiveTask = Task { [weak self] in
            await self?.receiveLoop()
        }
    }

    func disconnect() {
        receiveTask?.cancel()
        receiveTask = nil
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
        session?.invalidateAndCancel()
        session = nil
        state = .disconnected
    }

    @discardableResult
    func sendEnvelope(_ envelope: ProtocolEnvelope) -> Bool {
        guard let webSocket, let data = try? ProtocolJSON.encoder.encode(envelope), let text = String(data: data, encoding: .utf8) else {
            logger.warn("Skipped sending \(envelope.type) because LAN socket is not connected")
            return false
        }
        webSocket.send(.string(text)) { [weak self] error in
            if let error {
                Task { @MainActor in
                    self?.state = .failed
                    self?.logger.error("Failed to send \(envelope.type)", error)
                }
            }
        }
        return true
    }

    func sendClipboardEvent(_ event: ClipboardEvent, imageBytes: Data?) async -> Bool {
        guard sendEnvelope(ProtocolEnvelope(type: "clipboard_offer", event: event)) else {
            return false
        }
        guard let image = event.image, let imageBytes else {
            return true
        }

        let chunkSize = 16 * 1024
        let transferId = image.transferId ?? event.eventId
        let totalChunks = (imageBytes.count + chunkSize - 1) / chunkSize
        let descriptor = TransferDescriptor(
            transferId: transferId,
            eventId: event.eventId,
            totalChunks: totalChunks,
            totalBytes: Int64(imageBytes.count),
            checksumSha256: image.checksumSha256
        )
        guard sendEnvelope(ProtocolEnvelope(type: "transfer_begin", transfer: descriptor)) else {
            return false
        }

        for index in 0..<totalChunks {
            let start = index * chunkSize
            let end = min(imageBytes.count, start + chunkSize)
            let chunk = TransferChunk(
                transferId: transferId,
                chunkIndex: index,
                base64Payload: imageBytes[start..<end].base64EncodedString()
            )
            guard sendEnvelope(ProtocolEnvelope(type: "transfer_chunk", chunk: chunk)) else {
                return false
            }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }

        return sendEnvelope(ProtocolEnvelope(type: "transfer_complete", transfer: descriptor))
    }

    private func receiveLoop() async {
        while !Task.isCancelled, let webSocket {
            do {
                let message = try await webSocket.receive()
                let data: Data
                switch message {
                case .string(let text):
                    data = Data(text.utf8)
                case .data(let payload):
                    data = payload
                @unknown default:
                    continue
                }

                let envelope = try ProtocolJSON.decoder.decode(ProtocolEnvelope.self, from: data)
                if envelope.type == "peer_status", envelope.status == "ready" {
                    state = .ready
                }
                onEnvelope?(envelope)
            } catch {
                if !Task.isCancelled {
                    state = .failed
                    logger.error("LAN receive loop failed", error)
                }
                return
            }
        }
    }
}

private final class CertificatePinningDelegate: NSObject, URLSessionDelegate {
    private let expectedSha256: String
    private let warn: (String) -> Void

    init(expectedSha256: String, warn: @escaping (String) -> Void) {
        self.expectedSha256 = expectedSha256.lowercased()
        self.warn = warn
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let certificate = SecTrustGetCertificateAtIndex(trust, 0) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        let data = SecCertificateCopyData(certificate) as Data
        let actual = CryptoUtils.sha256Hex(data)
        guard actual.lowercased() == expectedSha256 else {
            warn("Certificate fingerprint mismatch: expected \(expectedSha256), got \(actual)")
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}

