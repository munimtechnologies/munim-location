//
//  MunimLocationMotion.swift
//  munim-location
//
//  Motion activity from the motion coprocessor (CMMotionActivityManager).
//  One activity stream is shared by every consumer: JavaScript
//  (`motionActivity` events) and the location sessions that pause while the
//  device is still (`pauseWhenStationary`). It runs only while at least one
//  consumer is registered. Main thread only.
//

import CoreMotion
import Foundation

final class MunimLocationMotion {
    static let shared = MunimLocationMotion()

    private let manager = CMMotionActivityManager()
    private var observers: [String: (MotionActivity) -> Void] = [:]
    private var running = false
    /// Latest reading of the running stream (nil once it stops).
    private var current: MotionActivity?
    /// Latest reading this process has seen.
    private(set) var last: MotionActivity?

    static var available: Bool { CMMotionActivityManager.isActivityAvailable() }

    static func permission() -> MotionPermission {
        guard available else { return .unavailable }
        switch CMMotionActivityManager.authorizationStatus() {
        case .authorized: return .granted
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notdetermined
        @unknown default: return .notdetermined
        }
    }

    /// Shows the Motion & Fitness prompt when the status is notDetermined.
    /// CoreMotion has no request API; the first query triggers the prompt and
    /// its handler runs once the person answered.
    func requestPermission(_ completion: @escaping (MotionPermission) -> Void) {
        MunimLocationCore.onMain {
            guard Self.permission() == .notdetermined else {
                completion(Self.permission())
                return
            }
            let now = Date()
            self.manager.queryActivityStarting(from: now.addingTimeInterval(-60), to: now, to: .main) { _, _ in
                completion(Self.permission())
            }
        }
    }

    /// Registers a consumer and starts the stream if needed. When the stream
    /// was already running its current activity is replayed, so the new
    /// consumer starts from the present state (a fresh stream reports it by
    /// itself).
    func addObserver(_ key: String, _ block: @escaping (MotionActivity) -> Void) {
        MunimLocationCore.onMain {
            let wasRunning = self.running
            self.observers[key] = block
            self.startIfNeeded()
            if wasRunning, let current = self.current { block(current) }
        }
    }

    func removeObserver(_ key: String) {
        MunimLocationCore.onMain {
            guard self.observers.removeValue(forKey: key) != nil else { return }
            if self.observers.isEmpty, self.running {
                self.running = false
                self.manager.stopActivityUpdates()
                // A stopped stream goes stale; the next start reports afresh.
                self.current = nil
            }
        }
    }

    private func startIfNeeded() {
        guard !running, Self.available else { return }
        running = true
        manager.startActivityUpdates(to: .main) { [weak self] activity in
            guard let self, let activity else { return }
            let converted = Self.convert(activity)
            if let current = self.current, current.type == converted.type, current.confidence == converted.confidence {
                return
            }
            self.current = converted
            self.last = converted
            self.observers.values.forEach { $0(converted) }
        }
    }

    static func convert(_ activity: CMMotionActivity) -> MotionActivity {
        // Several flags can be set at once (automotive + stationary at a red
        // light); the most specific motion wins.
        let type: MotionActivityType
        if activity.automotive {
            type = .automotive
        } else if activity.cycling {
            type = .cycling
        } else if activity.running {
            type = .running
        } else if activity.walking {
            type = .walking
        } else if activity.stationary {
            type = .still
        } else {
            type = .unknown
        }
        let confidence: MotionConfidence
        switch activity.confidence {
        case .high: confidence = .high
        case .medium: confidence = .medium
        default: confidence = .low
        }
        return MotionActivity(
            type: type,
            confidence: confidence,
            timestamp: MunimLocationConvert.millis(activity.startDate)
        )
    }

    static func dict(_ activity: MotionActivity) -> [String: Any] {
        [
            "type": activity.type.stringValue,
            "confidence": activity.confidence.stringValue,
            "timestamp": activity.timestamp,
        ]
    }
}

/// Pauses a location session while the device is still. `onChange(true)`
/// runs when a confident `still` arrives, `onChange(false)` when motion
/// resumes. Unknown or low-confidence readings change nothing.
final class StationaryGate {
    private let key: String
    private(set) var paused = false
    private let onChange: (Bool) -> Void

    init(key: String, onChange: @escaping (Bool) -> Void) {
        self.key = key
        self.onChange = onChange
    }

    func start() {
        guard MunimLocationMotion.available else { return }
        if MunimLocationMotion.permission() == .denied || MunimLocationMotion.permission() == .restricted {
            MunimLocationEvents.shared.emit("motionActivityError", [
                "code": "E_MOTION_PERMISSION_DENIED",
                "message": "pauseWhenStationary needs Motion & Fitness access; updates are not paused",
                "source": key,
            ])
            return
        }
        MunimLocationMotion.shared.addObserver(key) { [weak self] activity in
            self?.handle(activity)
        }
    }

    func stop() {
        MunimLocationMotion.shared.removeObserver(key)
        paused = false
    }

    private func handle(_ activity: MotionActivity) {
        let confident = activity.confidence != .low
        if activity.type == .still, confident, !paused {
            paused = true
            onChange(true)
        } else if paused, confident, activity.type != .still, activity.type != .unknown {
            paused = false
            onChange(false)
        }
    }
}
