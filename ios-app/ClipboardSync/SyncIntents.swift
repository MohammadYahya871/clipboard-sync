import AppIntents
import Foundation

struct SyncClipboardNowIntent: AppIntent {
    static var title: LocalizedStringResource = "Sync Clipboard Now"
    static var description = IntentDescription("Sends the current iOS pasteboard or latest screenshot to the paired Windows device.")

    @MainActor
    func perform() async throws -> some IntentResult {
        let repository = SyncRepository()
        repository.onAppForegrounded()
        repository.syncSmartNow(trigger: "shortcut")
        return .result()
    }
}

struct SyncLatestScreenshotIntent: AppIntent {
    static var title: LocalizedStringResource = "Sync Latest Screenshot"
    static var description = IntentDescription("Sends the latest iOS screenshot to the paired Windows device.")

    @MainActor
    func perform() async throws -> some IntentResult {
        let repository = SyncRepository()
        repository.onAppForegrounded()
        repository.syncLatestScreenshotNow(trigger: "shortcut-screenshot")
        return .result()
    }
}

struct ClipboardSyncShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SyncClipboardNowIntent(),
            phrases: [
                "Sync clipboard with \(.applicationName)",
                "Send clipboard with \(.applicationName)"
            ],
            shortTitle: "Sync Clipboard",
            systemImageName: "doc.on.clipboard"
        )
        AppShortcut(
            intent: SyncLatestScreenshotIntent(),
            phrases: [
                "Sync screenshot with \(.applicationName)"
            ],
            shortTitle: "Sync Screenshot",
            systemImageName: "photo"
        )
    }
}

