//
//  MunimLocationEvents.swift
//  munim-location
//
//  Native event channels to JavaScript:
//  - a typed location channel (Nitro structs, one call per batch) for watch,
//    background, and significant-change fixes;
//  - a JSON channel (`name` + payload) for everything else.
//  Background-origin events raised while no JavaScript listener is attached
//  (for example when iOS relaunches the app for a significant change, visit,
//  or region event before React Native has started) are appended to an
//  on-disk queue and replayed on attach.
//

import CoreLocation
import Foundation

final class MunimLocationEvents {
    static let shared = MunimLocationEvents()

    typealias LocationListener = (_ source: LocationBatchSource, _ watchId: Double, _ locations: [Location]) -> Void

    private let lock = NSLock()
    private var listener: ((String, String) -> Void)?
    private var locationListener: LocationListener?
    private let queue = PendingEventQueue(
        directoryName: "munim-location",
        maxEvents: 500
    )

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

    func setLocationListener(_ newListener: @escaping LocationListener) {
        lock.lock()
        locationListener = newListener
        lock.unlock()
    }

    func removeLocationListener() {
        lock.lock()
        locationListener = nil
        lock.unlock()
    }

    /// Emits an event. When `persist` is true and no listener is attached the
    /// event is written to the pending queue instead of being dropped.
    func emit(_ name: String, _ body: [String: Any], persist: Bool = false) {
        lock.lock()
        let current = listener
        lock.unlock()
        if let current {
            current(name, MunimLocationJSON.string(body))
        } else if persist {
            queue.append(name: name, payload: MunimLocationJSON.string(body))
        }
    }

    /// Delivers a batch of fixes. Uses the typed channel when it is attached
    /// (no JSON), else the JSON channel; background-origin batches fall back
    /// to the pending queue when neither is attached.
    func emitLocations(_ source: LocationBatchSource, watchId: Int, _ locations: [CLLocation]) {
        guard !locations.isEmpty else { return }
        lock.lock()
        let typed = locationListener
        lock.unlock()
        if let typed {
            typed(source, Double(watchId), locations.map { MunimLocationConvert.location($0) })
            return
        }
        let converted = locations.map { MunimLocationConvert.dict($0) }
        switch source {
        case .watch:
            emit("location", ["watchId": watchId, "locations": converted])
        case .background:
            emit("backgroundLocation", ["locations": converted], persist: true)
        case .significantchange:
            emit("significantLocationChange", ["locations": converted], persist: true)
        }
    }

    // MARK: - Pending queue

    func pendingJSON() -> String { queue.json() }

    func pendingCount() -> Int { queue.count() }

    func clearPending() { queue.clear() }
}

/// Append-only queue of `{ name, payload, timestamp }` JSON lines.
///
/// Appending writes one line (O(1)) instead of reading and rewriting the
/// whole queue; the file is trimmed to `maxEvents` only once it holds 1.5x
/// that many lines, so the rewrite cost is amortised.
final class PendingEventQueue {
    private let lock = NSLock()
    private let maxEvents: Int
    private let fileURL: URL
    private let legacyURL: URL
    private var cachedCount: Int?

    init(directoryName: String, maxEvents: Int) {
        self.maxEvents = maxEvents
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let directory = base.appendingPathComponent(directoryName, isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("pending-events.ndjson")
        legacyURL = directory.appendingPathComponent("pending-events.json")
    }

    func append(name: String, payload: String) {
        let entry: [String: Any] = [
            "name": name,
            "payload": payload,
            "timestamp": Date().timeIntervalSince1970 * 1000,
        ]
        guard var line = try? JSONSerialization.data(withJSONObject: entry) else { return }
        line.append(0x0A)
        lock.lock()
        defer { lock.unlock() }
        migrateLegacyLocked()
        if let handle = try? FileHandle(forUpdating: fileURL) {
            do {
                let end = try handle.seekToEnd()
                if end > 0 {
                    // Start on a fresh line if a crash left a torn last line.
                    try handle.seek(toOffset: end - 1)
                    let last = try handle.read(upToCount: 1)
                    try handle.seek(toOffset: end)
                    if last != Data([0x0A]) { line.insert(0x0A, at: 0) }
                }
                try handle.write(contentsOf: line)
            } catch {}
            try? handle.close()
        } else {
            try? line.write(to: fileURL, options: .atomic)
        }
        let count = (cachedCount ?? linesLocked().count - 1) + 1
        cachedCount = count
        if count > maxEvents + maxEvents / 2 {
            writeLocked(Array(linesLocked().suffix(maxEvents)))
        }
    }

    func json() -> String {
        lock.lock()
        defer { lock.unlock() }
        migrateLegacyLocked()
        return "[" + linesLocked().suffix(maxEvents).joined(separator: ",") + "]"
    }

    func count() -> Int {
        lock.lock()
        defer { lock.unlock() }
        migrateLegacyLocked()
        return min(linesLocked().count, maxEvents)
    }

    func clear() {
        lock.lock()
        try? FileManager.default.removeItem(at: fileURL)
        try? FileManager.default.removeItem(at: legacyURL)
        cachedCount = 0
        lock.unlock()
    }

    /// Complete, valid JSON lines (a crash mid-append can leave a torn last line).
    private func linesLocked() -> [String] {
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8) else {
            cachedCount = 0
            return []
        }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: true).compactMap { line -> String? in
            guard let lineData = line.data(using: .utf8),
                  (try? JSONSerialization.jsonObject(with: lineData)) is [String: Any] else {
                return nil
            }
            return String(line)
        }
        cachedCount = lines.count
        return lines
    }

    private func writeLocked(_ lines: [String]) {
        let text = lines.isEmpty ? "" : lines.joined(separator: "\n") + "\n"
        try? text.data(using: .utf8)?.write(to: fileURL, options: .atomic)
        cachedCount = lines.count
    }

    /// Moves events from the pre-0.4 JSON-array file into the line queue.
    private func migrateLegacyLocked() {
        guard FileManager.default.fileExists(atPath: legacyURL.path) else { return }
        defer { try? FileManager.default.removeItem(at: legacyURL) }
        guard let data = try? Data(contentsOf: legacyURL),
              let events = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              !events.isEmpty else {
            return
        }
        let legacy = events.compactMap { event -> String? in
            guard let lineData = try? JSONSerialization.data(withJSONObject: event) else { return nil }
            return String(data: lineData, encoding: .utf8)
        }
        writeLocked(Array((legacy + linesLocked()).suffix(maxEvents)))
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
