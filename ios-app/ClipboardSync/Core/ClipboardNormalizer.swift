import Foundation
import UIKit

final class ClipboardNormalizer {
    private let store: TrustedDeviceStore
    private let imageCache: ImageCacheStore

    init(store: TrustedDeviceStore, imageCache: ImageCacheStore) {
        self.store = store
        self.imageCache = imageCache
    }

    func normalizeCurrentPasteboard() -> NormalizedClipboard? {
        let pasteboard = UIPasteboard.general
        if let image = pasteboard.image {
            return normalizeImage(image, previewUri: nil)
        }
        if let url = pasteboard.url {
            return normalizeText(url.absoluteString, forcedType: .url)
        }
        if let text = pasteboard.string?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
            return normalizeText(text, forcedType: nil)
        }
        return nil
    }

    func normalizeText(_ text: String, forcedType: ContentType? = nil) -> NormalizedClipboard {
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
        let hash = CryptoUtils.sha256Hex(normalized)
        let type = forcedType ?? (URL(string: normalized)?.scheme?.hasPrefix("http") == true ? .url : .text)
        let deviceId = store.localDeviceId
        let event = ClipboardEvent(
            eventId: CryptoUtils.uuidV7(),
            sourceDeviceId: deviceId,
            originatedAtUtc: Date.utcNowString,
            contentType: type,
            mimeType: "text/plain",
            payloadSizeBytes: Int64(Data(normalized.utf8).count),
            contentHashSha256: hash,
            dedupeKey: "\(deviceId):\(hash)",
            transferState: .queued,
            textPayload: normalized,
            image: nil
        )
        return NormalizedClipboard(event: event, imageBytes: nil, previewText: String(normalized.prefix(120)), previewUri: nil, fromRemote: false)
    }

    func normalizeImage(_ image: UIImage, previewUri: String?) -> NormalizedClipboard? {
        guard let (cached, bytes) = imageCache.cacheImage(image) else { return nil }
        let deviceId = store.localDeviceId
        let transferId = CryptoUtils.uuidV7()
        let event = ClipboardEvent(
            eventId: CryptoUtils.uuidV7(),
            sourceDeviceId: deviceId,
            originatedAtUtc: Date.utcNowString,
            contentType: .image,
            mimeType: "image/png",
            payloadSizeBytes: cached.byteSize,
            contentHashSha256: cached.checksumSha256,
            dedupeKey: "\(deviceId):\(cached.checksumSha256)",
            transferState: .queued,
            textPayload: nil,
            image: ImageMetadata(
                width: cached.width,
                height: cached.height,
                byteSize: cached.byteSize,
                checksumSha256: cached.checksumSha256,
                encoding: "png",
                transferId: transferId
            )
        )
        return NormalizedClipboard(
            event: event,
            imageBytes: bytes,
            previewText: "Image \(cached.width)x\(cached.height)",
            previewUri: previewUri ?? cached.url.absoluteString,
            fromRemote: false
        )
    }
}

