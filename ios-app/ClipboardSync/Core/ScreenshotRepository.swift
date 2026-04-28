import Foundation
import Photos
import UIKit

struct ScreenshotCandidate {
    var assetIdentifier: String
    var displayName: String
    var image: UIImage
}

final class ScreenshotRepository {
    func latestScreenshot(maxAgeMillis: TimeInterval = 10 * 60 * 1000) async -> ScreenshotCandidate? {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard status == .authorized || status == .limited else { return nil }

        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = 20
        let minDate = Date().addingTimeInterval(-(maxAgeMillis / 1000))
        options.predicate = NSPredicate(format: "creationDate >= %@", minDate as NSDate)
        let assets = PHAsset.fetchAssets(with: .image, options: options)
        guard assets.count > 0 else { return nil }

        for index in 0..<assets.count {
            let asset = assets.object(at: index)
            if asset.mediaSubtypes.contains(.photoScreenshot),
               let image = await requestImage(asset: asset) {
                return ScreenshotCandidate(assetIdentifier: asset.localIdentifier, displayName: "Latest screenshot", image: image)
            }
        }
        return nil
    }

    private func requestImage(asset: PHAsset) async -> UIImage? {
        await withCheckedContinuation { continuation in
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            options.resizeMode = .none
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                guard let data else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: UIImage(data: data))
            }
        }
    }
}

