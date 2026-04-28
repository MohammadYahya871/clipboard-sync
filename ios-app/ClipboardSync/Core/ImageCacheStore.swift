import Foundation
import UIKit

struct CachedImage {
    var url: URL
    var width: Int
    var height: Int
    var byteSize: Int64
    var checksumSha256: String
}

final class ImageCacheStore {
    private let directory: URL

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        self.directory = base.appendingPathComponent("ClipboardSyncImages", isDirectory: true)
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
    }

    func cleanup() {
        guard let items = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey]) else {
            return
        }
        let cutoff = Date().addingTimeInterval(-7 * 24 * 60 * 60)
        for item in items {
            let values = try? item.resourceValues(forKeys: [.contentModificationDateKey])
            if (values?.contentModificationDate ?? .distantPast) < cutoff {
                try? FileManager.default.removeItem(at: item)
            }
        }
    }

    func cacheImage(_ image: UIImage, id: String = CryptoUtils.uuidV7()) -> (CachedImage, Data)? {
        guard let data = image.pngData() else { return nil }
        return cachePngBytes(data, id: id)
    }

    func cachePngBytes(_ data: Data, id: String) -> (CachedImage, Data)? {
        guard let image = UIImage(data: data) else { return nil }
        let url = directory.appendingPathComponent("\(id).png")
        do {
            try data.write(to: url, options: .atomic)
            let cached = CachedImage(
                url: url,
                width: Int(image.size.width * image.scale),
                height: Int(image.size.height * image.scale),
                byteSize: Int64(data.count),
                checksumSha256: CryptoUtils.sha256Hex(data)
            )
            return (cached, data)
        } catch {
            return nil
        }
    }
}

