import Foundation
import UIKit

final class ClipboardApplyUseCase {
    private let imageCache: ImageCacheStore

    init(imageCache: ImageCacheStore) {
        self.imageCache = imageCache
    }

    @MainActor
    func applyRemoteClip(event: ClipboardEvent, imageBytes: Data?) -> Bool {
        switch event.contentType {
        case .text, .url:
            guard let text = event.textPayload else { return false }
            UIPasteboard.general.string = text
            return true
        case .image:
            guard let bytes = imageBytes, let image = UIImage(data: bytes) else { return false }
            _ = imageCache.cachePngBytes(bytes, id: event.image?.transferId ?? event.eventId)
            UIPasteboard.general.image = image
            return true
        case .mixedUnsupported:
            return false
        }
    }
}
