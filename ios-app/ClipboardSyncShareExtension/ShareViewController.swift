import Social
import UIKit
import UniformTypeIdentifiers

final class ShareViewController: SLComposeServiceViewController {
    override func isContentValid() -> Bool {
        true
    }

    override func didSelectPost() {
        Task {
            await collectSharedItems()
            extensionContext?.completeRequest(returningItems: nil)
        }
    }

    override func configurationItems() -> [Any]! {
        []
    }

    private func collectSharedItems() async {
        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else { return }
        var shared: [SharedShareItem] = []

        for item in extensionItems {
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier),
                   let image = await loadImage(from: provider),
                   let data = image.pngData() {
                    shared.append(SharedShareItem(kind: .image, text: nil, base64Data: data.base64EncodedString()))
                    continue
                }

                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                   let url = await loadURL(from: provider) {
                    shared.append(SharedShareItem(kind: .url, text: url.absoluteString, base64Data: nil))
                    continue
                }

                if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                   let text = await loadText(from: provider), !text.isEmpty {
                    shared.append(SharedShareItem(kind: .text, text: text, base64Data: nil))
                }
            }
        }

        guard !shared.isEmpty,
              let defaults = UserDefaults(suiteName: SharedAppGroup.identifier) else { return }
        var current = (try? JSONDecoder().decode([SharedShareItem].self, from: defaults.data(forKey: SharedAppGroup.inboxKey) ?? Data())) ?? []
        current.append(contentsOf: shared)
        if let data = try? JSONEncoder().encode(current) {
            defaults.set(data, forKey: SharedAppGroup.inboxKey)
        }
    }

    private func loadImage(from provider: NSItemProvider) async -> UIImage? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.image.identifier, options: nil) { item, _ in
                if let image = item as? UIImage {
                    continuation.resume(returning: image)
                } else if let url = item as? URL, let data = try? Data(contentsOf: url) {
                    continuation.resume(returning: UIImage(data: data))
                } else if let data = item as? Data {
                    continuation.resume(returning: UIImage(data: data))
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    private func loadURL(from provider: NSItemProvider) async -> URL? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                if let url = item as? URL {
                    continuation.resume(returning: url)
                } else if let text = item as? String {
                    continuation.resume(returning: URL(string: text))
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }

    private func loadText(from provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                if let text = item as? String {
                    continuation.resume(returning: text)
                } else if let data = item as? Data {
                    continuation.resume(returning: String(data: data, encoding: .utf8))
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }
}

private enum SharedAppGroup {
    static let identifier = "group.com.clipboardsync.ios"
    static let inboxKey = "shared_inbox"
}

private enum SharedShareKind: String, Codable {
    case text
    case url
    case image
}

private struct SharedShareItem: Codable {
    var kind: SharedShareKind
    var text: String?
    var base64Data: String?
}

