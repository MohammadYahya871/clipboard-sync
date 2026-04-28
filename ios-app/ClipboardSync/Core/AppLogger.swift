import Foundation

enum LogLevel: String, Codable {
    case info = "INFO"
    case warn = "WARN"
    case error = "ERROR"
}

struct LogEntry: Codable, Identifiable, Equatable {
    var id = UUID()
    var timestampUtc: String
    var level: LogLevel
    var message: String
}

@MainActor
final class AppLogger: ObservableObject {
    @Published private(set) var entries: [LogEntry] = []

    func info(_ message: String) {
        append(.info, message)
    }

    func warn(_ message: String) {
        append(.warn, message)
    }

    func error(_ message: String, _ error: Error? = nil) {
        if let error {
            append(.error, "\(message): \(error.localizedDescription)")
        } else {
            append(.error, message)
        }
    }

    func clear() {
        entries.removeAll()
    }

    private func append(_ level: LogLevel, _ message: String) {
        let entry = LogEntry(timestampUtc: Date.utcNowString, level: level, message: message)
        entries = Array(([entry] + entries).prefix(100))
    }
}

