import SwiftUI

@main
struct ClipboardSyncIOSApp: App {
    @StateObject private var repository = SyncRepository()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView(repository: repository)
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        repository.onAppForegrounded()
                    }
                }
        }
    }
}

