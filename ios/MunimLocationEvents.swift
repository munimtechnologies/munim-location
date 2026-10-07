//
//  MunimLocationEvents.swift
//  munim-location
//
//  One native event channel to JavaScript. Background-origin events raised
//  while no JavaScript listener is attached (for example when iOS relaunches
//  the app for a significant change, visit, or region event before React
//  Native has started) are persisted to disk and replayed on attach.
//

import Foundation

final class MunimLocationEvents {
    static let shared = MunimLocationEvents()

    private let lock = NSLock()
    private var listener: ((String, String) -> Void)?
    private let maxPendingEvents = 500
    private lazy var pendingURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let directory = base.appendingPathComponent("munim-location", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("pending-events.json")
    }()

    var hasListener: Bool {
        lock.lock()
        defer { lock.unlock() }
        return listener != nil
    }

    func setListener(_ newListener: @escaping (String, String) -> Void) {
        lock.lock()
        listener = newListener
        lock.unlock()
    }

    func removeListener() {
        lock.lock()
        listener = nil
        lock.unlock()
    }

    /// Emits an event. When `persist` is true and no listener is attached the
    /// event is written to the pending queue instead of being dropped.
    func emit(_ name: String, _ body: [String: Any], persist: Bool = false) {
        let payload = MunimLocationJSON.string(body)
        lock.lock()
        let current = listener
        lock.unlock()
        if let current {
            current(name, payload)
        } else if persist {
            appendPending(name: name, payload: payload)
        }
    }

    // MARK: - Pending queue

    func pendingJSON() -> String {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: pendingURL),
              let text = String(data: data, encoding: .utf8),
              !text.isEmpty else {
            return "[]"
        }
        return text
    }

    func pendingCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return readPendingLocked().count
    }

    func clearPending() {
        lock.lock()
        try? FileManager.default.removeItem(at: pendingURL)
        lock.unlock()
    }

    private func appendPending(name: String, payload: String) {
        lock.lock()
        defer { lock.unlock() }
        var events = readPendingLocked()
        events.append([
            "name": name,
            "payload": payload,
            "timestamp": Date().timeIntervalSince1970 * 1000,
        ])
        if events.count > maxPendingEvents {
            events.removeFirst(events.count - maxPendingEvents)
        }
        if let data = try? JSONSerialization.data(withJSONObject: events) {
            try? data.write(to: pendingURL, options: .atomic)
        }
    }

    private func readPendingLocked() -> [[String: Any]] {
        guard let data = try? Data(contentsOf: pendingURL),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return array
    }
}

enum MunimLocationJSON {
    static func string(_ value: Any) -> String {
        let sanitized = sanitize(value)
        guard JSONSerialization.isValidJSONObject(sanitized),
              let data = try? JSONSerialization.data(withJSONObject: sanitized),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    /// JSONSerialization rejects NaN and infinity; drop them.
    private static func sanitize(_ value: Any) -> Any {
        switch value {
        case let dictionary as [String: Any]:
            var result: [String: Any] = [:]
            for (key, item) in dictionary {
                if let number = item as? Double, !number.isFinite { continue }
                if item is NSNull { continue }
                result[key] = sanitize(item)
            }
            return result
        case let array as [Any]:
            return array.map { sanitize($0) }
        default:
            return value
        }
    }
}
