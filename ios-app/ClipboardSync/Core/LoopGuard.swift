import Foundation

final class LoopGuard {
    private var seenEventIds: [String: Date] = [:]
    private var remoteAppliedHashes: [String: Date] = [:]
    private let ttl: TimeInterval = 8

    func rememberSeenEvent(_ eventId: String) {
        prune()
        seenEventIds[eventId] = Date()
    }

    func hasSeenEvent(_ eventId: String) -> Bool {
        prune()
        return seenEventIds[eventId] != nil
    }

    func markRemoteApplied(_ hash: String) {
        prune()
        remoteAppliedHashes[hash] = Date()
    }

    func shouldSuppressLocal(hash: String) -> Bool {
        prune()
        return remoteAppliedHashes[hash] != nil
    }

    private func prune() {
        let cutoff = Date().addingTimeInterval(-ttl)
        seenEventIds = seenEventIds.filter { $0.value > cutoff }
        remoteAppliedHashes = remoteAppliedHashes.filter { $0.value > cutoff }
    }
}

