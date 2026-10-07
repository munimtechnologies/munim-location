//
//  MunimLocationCore.swift
//  munim-location
//
//  Process-wide CoreLocation engine. It is created at app launch (see
//  MunimLocationLoader.m) so that significant-change, visit, and region
//  relaunches are serviced before React Native starts, and it outlives any
//  JavaScript runtime. All CoreLocation work happens on the main thread.
//

import CoreLocation
import CoreMotion
import Foundation
import NitroModules
import UIKit

private let defaultsPrefix = "MunimLocation."

@_cdecl("munim_location_install_launch_observer")
public func munimLocationInstallLaunchObserver() {
    NotificationCenter.default.addObserver(
        forName: UIApplication.didFinishLaunchingNotification,
        object: nil,
        queue: .main
    ) { notification in
        MunimLocationCore.shared.bootstrap(launchOptions: notification.userInfo)
    }
}

final class MunimLocationCore: NSObject, CLLocationManagerDelegate {
    static let shared = MunimLocationCore()

    private let defaults = UserDefaults.standard
    private let idLock = NSLock()
    private var nextId = 1
    private var bootstrapped = false
    private(set) var launchedForLocation = false

    // Managers. `authManager` handles authorization and cached locations;
    // `monitoringManager` handles significant changes, visits, legacy
    // regions, and beacons; `backgroundManager` handles continuous
    // background updates; `headingManager` handles the compass.
    private lazy var authManager: CLLocationManager = makeManager()
    private lazy var monitoringManager: CLLocationManager = makeManager()
    private lazy var backgroundManager: CLLocationManager = makeManager()
    private lazy var headingManager: CLLocationManager = makeManager()

    private var permissionWaiters: [PromptWaiter] = []
    private var watches: [Int: WatchSession] = [:]
    private var oneShots: [ObjectIdentifier: OneShotRequest] = [:]
    private var serviceSessions: [String: AnyObject] = [:]
    private var sessionTasks: [String: Task<Void, Never>] = [:]
    private var rangingConstraints: [String: AnyObject] = [:]
    private var dwellTimers: [String: DispatchWorkItem] = [:]
    private var expiryTimers: [String: DispatchWorkItem] = [:]
    private var stateWaiters: [String: [(GeofenceState) -> Void]] = [:]
    private var headingWaiters: [(Heading) -> Void] = []
    private var headingRequestedByUser = false
    private var showsHeadingCalibration = true
    private var lastHeading: Heading?
    private let altimeter = CMAltimeter()
    private var monitorEngine: AnyObject?

    private override init() {
        super.init()
    }

    private func makeManager() -> CLLocationManager {
        let manager = CLLocationManager()
        manager.delegate = self
        return manager
    }

    func generateId() -> Int {
        idLock.lock()
        defer { idLock.unlock() }
        let id = nextId
        nextId += 1
        return id
    }

    static func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    // MARK: - Launch

    func bootstrap(launchOptions: [AnyHashable: Any]?) {
        MunimLocationCore.onMain {
            guard !self.bootstrapped else { return }
            self.bootstrapped = true
            if launchOptions?[UIApplication.LaunchOptionsKey.location] != nil {
                self.launchedForLocation = true
                MunimLocationEvents.shared.emit(
                    "backgroundLaunch",
                    ["reason": "location", "timestamp": MunimLocationConvert.nowMs()],
                    persist: true
                )
            }
            self.restorePersistedServices()
        }
    }

    /// Recreates the managers for persisted services. Apple requires a
    /// configured CLLocationManager with a delegate early in launch to
    /// receive the events that relaunched the app.
    private func restorePersistedServices() {
        if defaults.bool(forKey: defaultsPrefix + "significantChanges") {
            monitoringManager.startMonitoringSignificantLocationChanges()
        }
        if defaults.bool(forKey: defaultsPrefix + "visits") {
            monitoringManager.startMonitoringVisits()
        }
        let regions = storedRegions()
        if !regions.isEmpty {
            if #available(iOS 17.0, *) {
                Task { _ = await self.engine().monitor() }
            } else {
                _ = monitoringManager.monitoredRegions
            }
            for (identifier, meta) in regions {
                scheduleExpiry(identifier: identifier, meta: meta)
            }
        }
        if defaults.bool(forKey: defaultsPrefix + "background.running"),
           let stored = defaults.dictionary(forKey: defaultsPrefix + "background.options") {
            configureBackgroundManager(stored)
            backgroundManager.startUpdatingLocation()
        }
    }

    // MARK: - Helpers

    var backgroundModeEnabled: Bool {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        return modes.contains("location")
    }

    var authorizationStatus: CLAuthorizationStatus {
        if #available(iOS 14.0, *) {
            return authManager.authorizationStatus
        }
        return CLLocationManager.authorizationStatus()
    }

    var isAuthorized: Bool {
        let status = authorizationStatus
        return status == .authorizedAlways || status == .authorizedWhenInUse
    }

    var accuracyAuthorization: AccuracyAuthorization {
        if #available(iOS 14.0, *) {
            return authManager.accuracyAuthorization == .fullAccuracy ? .full : .reduced
        }
        return .full
    }

    static func servicesEnabled(_ completion: @escaping (Bool) -> Void) {
        // locationServicesEnabled() blocks; Apple warns against calling it on main.
        DispatchQueue.global(qos: .userInitiated).async {
            let enabled = CLLocationManager.locationServicesEnabled()
            completion(enabled)
        }
    }

    // MARK: - Permissions

    func permissionStatus(_ completion: @escaping (PermissionStatus) -> Void) {
        MunimLocationCore.servicesEnabled { enabled in
            MunimLocationCore.onMain {
                completion(self.buildPermissionStatus(servicesEnabled: enabled))
            }
        }
    }

    private func buildPermissionStatus(servicesEnabled: Bool) -> PermissionStatus {
        let status = authorizationStatus
        let accuracy = accuracyAuthorization
        let foreground = status == .authorizedWhenInUse || status == .authorizedAlways
        return PermissionStatus(
            status: MunimLocationConvert.authorizationStatus(status),
            accuracy: accuracy,
            foreground: foreground,
            background: status == .authorizedAlways,
            precise: foreground && accuracy == .full,
            canAskAgain: status == .notDetermined || status == .authorizedWhenInUse,
            locationServicesEnabled: servicesEnabled
        )
    }

    func requestForeground(_ completion: @escaping (PermissionStatus) -> Void) {
        MunimLocationCore.onMain {
            if self.authorizationStatus == .notDetermined {
                self.waitForPrompt(completion)
                self.authManager.requestWhenInUseAuthorization()
            } else {
                self.permissionStatus(completion)
            }
        }
    }

    func requestBackground(_ completion: @escaping (PermissionStatus) -> Void) {
        MunimLocationCore.onMain {
            let status = self.authorizationStatus
            // notDetermined: the provisional-always flow (the user sees the
            // when-in-use prompt; iOS later offers the upgrade itself).
            // whenInUse: iOS shows the one-time upgrade prompt.
            if status == .notDetermined || status == .authorizedWhenInUse {
                self.waitForPrompt(completion)
                self.authManager.requestAlwaysAuthorization()
            } else {
                self.permissionStatus(completion)
            }
        }
    }

    /// Resolves when authorization changes, or when the prompt (if any) was
    /// dismissed. iOS does not call back when it decides not to show a prompt
    /// (for example an always upgrade that was already offered once).
    private func waitForPrompt(_ completion: @escaping (PermissionStatus) -> Void) {
        let waiter = PromptWaiter { [weak self] in
            guard let self else { return }
            self.permissionWaiters.removeAll { $0.finished }
            self.permissionStatus(completion)
        }
        permissionWaiters.append(waiter)
        waiter.arm()
    }

    func requestTemporaryFullAccuracy(purposeKey: String, _ completion: @escaping (Result<AccuracyAuthorization, Error>) -> Void) {
        MunimLocationCore.onMain {
            guard #available(iOS 14.0, *) else {
                completion(.success(.full))
                return
            }
            if self.authManager.accuracyAuthorization == .fullAccuracy {
                completion(.success(.full))
                return
            }
            self.authManager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: purposeKey) { error in
                MunimLocationCore.onMain {
                    if let error {
                        completion(.failure(MunimLocationError.make("E_TEMPORARY_FULL_ACCURACY", error.localizedDescription)))
                    } else {
                        completion(.success(self.accuracyAuthorization))
                    }
                }
            }
        }
    }

    func openSettings(_ completion: @escaping (Bool) -> Void) {
        MunimLocationCore.onMain {
            guard let url = URL(string: UIApplication.openSettingsURLString) else {
                completion(false)
                return
            }
            UIApplication.shared.open(url, options: [:]) { completion($0) }
        }
    }

    // MARK: - Service sessions (iOS 17/18)

    func startServiceSession(authorization: ServiceSessionAuthorization, purposeKey: String) -> String {
        guard #available(iOS 18.0, *) else { return "" }
        let id = "service-\(generateId())"
        MunimLocationCore.onMain {
            let requirement: CLServiceSession.AuthorizationRequirement
            switch authorization {
            case .none: requirement = .none
            case .wheninuse: requirement = .whenInUse
            case .always: requirement = .always
            }
            let session = purposeKey.isEmpty
                ? CLServiceSession(authorization: requirement)
                : CLServiceSession(authorization: requirement, fullAccuracyPurposeKey: purposeKey)
            self.serviceSessions[id] = session
            self.sessionTasks[id] = Task {
                do {
                    for try await diagnostic in session.diagnostics {
                        if Task.isCancelled { break }
                        MunimLocationEvents.shared.emit("serviceSessionDiagnostic", [
                            "sessionId": id,
                            "authorizationDenied": diagnostic.authorizationDenied,
                            "authorizationDeniedGlobally": diagnostic.authorizationDeniedGlobally,
                            "authorizationRestricted": diagnostic.authorizationRestricted,
                            "insufficientlyInUse": diagnostic.insufficientlyInUse,
                            "serviceSessionRequired": diagnostic.serviceSessionRequired,
                            "fullAccuracyDenied": diagnostic.fullAccuracyDenied,
                            "alwaysAuthorizationDenied": diagnostic.alwaysAuthorizationDenied,
                            "authorizationRequestInProgress": diagnostic.authorizationRequestInProgress,
                        ])
                    }
                } catch {}
            }
        }
        return id
    }

    func startBackgroundActivitySession() -> String {
        guard #available(iOS 17.0, *) else { return "" }
        let id = "activity-\(generateId())"
        MunimLocationCore.onMain {
            let session = CLBackgroundActivitySession()
            self.serviceSessions[id] = session
            if #available(iOS 18.0, *) {
                self.sessionTasks[id] = Task {
                    do {
                        for try await diagnostic in session.diagnostics {
                            if Task.isCancelled { break }
                            MunimLocationEvents.shared.emit("backgroundActivitySessionDiagnostic", [
                                "sessionId": id,
                                "authorizationDenied": diagnostic.authorizationDenied,
                                "authorizationDeniedGlobally": diagnostic.authorizationDeniedGlobally,
                                "authorizationRestricted": diagnostic.authorizationRestricted,
                                "insufficientlyInUse": diagnostic.insufficientlyInUse,
                                "serviceSessionRequired": diagnostic.serviceSessionRequired,
                                "authorizationRequestInProgress": diagnostic.authorizationRequestInProgress,
                            ])
                        }
                    } catch {}
                }
            }
        }
        return id
    }

    func stopSession(_ id: String) {
        MunimLocationCore.onMain {
            self.sessionTasks.removeValue(forKey: id)?.cancel()
            guard let session = self.serviceSessions.removeValue(forKey: id) else { return }
            if #available(iOS 18.0, *), let serviceSession = session as? CLServiceSession {
                serviceSession.invalidate()
            } else if #available(iOS 17.0, *), let activitySession = session as? CLBackgroundActivitySession {
                activitySession.invalidate()
            }
        }
    }

    // MARK: - Positions

    func currentPosition(_ options: CurrentPositionOptions, _ completion: @escaping (Result<Location, Error>) -> Void) {
        MunimLocationCore.servicesEnabled { enabled in
            MunimLocationCore.onMain {
                guard enabled else {
                    completion(.failure(MunimLocationError.servicesDisabled))
                    return
                }
                guard self.isAuthorized else {
                    completion(.failure(MunimLocationError.permissionDenied))
                    return
                }
                if options.maximumAgeMs > 0, let cached = self.authManager.location,
                   -cached.timestamp.timeIntervalSinceNow * 1000 <= options.maximumAgeMs,
                   cached.horizontalAccuracy >= 0,
                   cached.horizontalAccuracy <= MunimLocationConvert.acceptableAccuracy(options.accuracy) {
                    completion(.success(MunimLocationConvert.location(cached)))
                    return
                }
                let request = OneShotRequest(options: options)
                let key = ObjectIdentifier(request)
                self.oneShots[key] = request
                request.start { result in
                    self.oneShots.removeValue(forKey: key)
                    completion(result)
                }
            }
        }
    }

    func lastKnownPosition(_ options: LastKnownPositionOptions, _ completion: @escaping (Location?) -> Void) {
        MunimLocationCore.onMain {
            guard self.isAuthorized, let cached = self.authManager.location, cached.horizontalAccuracy >= 0 else {
                completion(nil)
                return
            }
            if options.maximumAgeMs > 0, -cached.timestamp.timeIntervalSinceNow * 1000 > options.maximumAgeMs {
                completion(nil)
                return
            }
            if options.requiredAccuracy > 0, cached.horizontalAccuracy > options.requiredAccuracy {
                completion(nil)
                return
            }
            completion(MunimLocationConvert.location(cached))
        }
    }

    func watch(_ options: WatchOptions) -> Int {
        let id = generateId()
        MunimLocationCore.onMain {
            let session = WatchSession(id: id, options: options, core: self)
            self.watches[id] = session
            session.start()
        }
        return id
    }

    func clearWatch(_ id: Int) {
        MunimLocationCore.onMain {
            self.watches.removeValue(forKey: id)?.stop()
        }
    }

    func clearAllWatches() {
        MunimLocationCore.onMain {
            self.watches.values.forEach { $0.stop() }
            self.watches.removeAll()
        }
    }

    // MARK: - Background

    private func backgroundOptionsDict(_ options: WatchOptions) -> [String: Any] {
        [
            "accuracy": options.accuracy.stringValue,
            "distanceFilter": options.distanceFilter,
            "activityType": options.activityType.stringValue,
            "pauses": options.pausesLocationUpdatesAutomatically,
            "showsIndicator": options.showsBackgroundLocationIndicator,
        ]
    }

    private func configureBackgroundManager(_ stored: [String: Any]) {
        let manager = backgroundManager
        let accuracy = LocationAccuracy(fromString: stored["accuracy"] as? String ?? "") ?? .best
        manager.desiredAccuracy = MunimLocationConvert.desiredAccuracy(accuracy)
        let distance = stored["distanceFilter"] as? Double ?? 0
        manager.distanceFilter = distance > 0 ? distance : kCLDistanceFilterNone
        let activity = ActivityType(fromString: stored["activityType"] as? String ?? "") ?? .other
        manager.activityType = MunimLocationConvert.activityType(activity)
        manager.pausesLocationUpdatesAutomatically = stored["pauses"] as? Bool ?? false
        if backgroundModeEnabled {
            manager.allowsBackgroundLocationUpdates = true
        }
        manager.showsBackgroundLocationIndicator = stored["showsIndicator"] as? Bool ?? true
    }

    func startBackground(_ options: WatchOptions, _ completion: @escaping (Result<Bool, Error>) -> Void) {
        MunimLocationCore.onMain {
            guard self.backgroundModeEnabled else {
                completion(.failure(MunimLocationError.backgroundModeMissing))
                return
            }
            guard self.isAuthorized else {
                completion(.failure(MunimLocationError.permissionDenied))
                return
            }
            let stored = self.backgroundOptionsDict(options)
            self.defaults.set(stored, forKey: defaultsPrefix + "background.options")
            self.defaults.set(true, forKey: defaultsPrefix + "background.running")
            self.configureBackgroundManager(stored)
            self.backgroundManager.startUpdatingLocation()
            completion(.success(true))
        }
    }

    func stopBackground() {
        MunimLocationCore.onMain {
            self.defaults.set(false, forKey: defaultsPrefix + "background.running")
            self.backgroundManager.stopUpdatingLocation()
            if self.backgroundModeEnabled {
                self.backgroundManager.allowsBackgroundLocationUpdates = false
            }
        }
    }

    func startSignificantChanges(_ completion: @escaping (Result<Bool, Error>) -> Void) {
        MunimLocationCore.onMain {
            guard CLLocationManager.significantLocationChangeMonitoringAvailable() else {
                completion(.failure(MunimLocationError.unsupported("Significant-change monitoring")))
                return
            }
            self.defaults.set(true, forKey: defaultsPrefix + "significantChanges")
            self.monitoringManager.startMonitoringSignificantLocationChanges()
            completion(.success(true))
        }
    }

    func stopSignificantChanges() {
        MunimLocationCore.onMain {
            self.defaults.set(false, forKey: defaultsPrefix + "significantChanges")
            self.monitoringManager.stopMonitoringSignificantLocationChanges()
        }
    }

    func startVisits(_ completion: @escaping (Result<Bool, Error>) -> Void) {
        MunimLocationCore.onMain {
            self.defaults.set(true, forKey: defaultsPrefix + "visits")
            self.monitoringManager.startMonitoringVisits()
            completion(.success(true))
        }
    }

    func stopVisits() {
        MunimLocationCore.onMain {
            self.defaults.set(false, forKey: defaultsPrefix + "visits")
            self.monitoringManager.stopMonitoringVisits()
        }
    }

    func backgroundStatus() -> BackgroundStatus {
        let running = defaults.bool(forKey: defaultsPrefix + "background.running")
        return BackgroundStatus(
            running: running,
            mode: running ? "ios" : "",
            significantChanges: defaults.bool(forKey: defaultsPrefix + "significantChanges"),
            visits: defaults.bool(forKey: defaultsPrefix + "visits"),
            geofenceCount: Double(storedRegions().values.filter { ($0["kind"] as? String) != "beacon" }.count),
            pendingEventCount: Double(MunimLocationEvents.shared.pendingCount())
        )
    }

    // MARK: - Regions (geofences and beacon monitoring)

    var usesMonitor: Bool {
        if #available(iOS 17.0, *) { return true }
        return false
    }

    @available(iOS 17.0, *)
    private func engine() -> MonitorEngine {
        if let existing = monitorEngine as? MonitorEngine { return existing }
        let created = MonitorEngine { [weak self] identifier, state, diagnostics in
            MunimLocationCore.onMain {
                self?.handleRegionState(identifier: identifier, state: state, extra: diagnostics)
            }
        }
        monitorEngine = created
        return created
    }

    private func storedRegions() -> [String: [String: Any]] {
        defaults.dictionary(forKey: defaultsPrefix + "regions") as? [String: [String: Any]] ?? [:]
    }

    private func saveRegions(_ regions: [String: [String: Any]]) {
        defaults.set(regions, forKey: defaultsPrefix + "regions")
    }

    private func updateRegion(_ identifier: String, _ mutate: (inout [String: Any]) -> Void) {
        var regions = storedRegions()
        guard var meta = regions[identifier] else { return }
        mutate(&meta)
        regions[identifier] = meta
        saveRegions(regions)
    }

    func addGeofence(_ region: GeofenceRegion, _ completion: @escaping (Result<Void, Error>) -> Void) {
        MunimLocationCore.onMain {
            guard CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else {
                completion(.failure(MunimLocationError.unsupported("Region monitoring")))
                return
            }
            var regions = self.storedRegions()
            if regions[region.identifier] == nil, regions.count >= 20 {
                completion(.failure(MunimLocationError.make("E_GEOFENCE_LIMIT", "iOS monitors at most 20 regions per app")))
                return
            }
            var meta = MunimLocationConvert.dict(region)
            meta["kind"] = "circle"
            if region.expirationMs > 0 {
                meta["expiresAt"] = MunimLocationConvert.nowMs() + region.expirationMs
            }
            regions[region.identifier] = meta
            self.saveRegions(regions)
            self.scheduleExpiry(identifier: region.identifier, meta: meta)

            let center = CLLocationCoordinate2D(latitude: region.latitude, longitude: region.longitude)
            let radius = min(region.radius, self.monitoringManager.maximumRegionMonitoringDistance)
            if #available(iOS 17.0, *) {
                let engine = self.engine()
                Task {
                    await engine.add(
                        CLMonitor.CircularGeographicCondition(center: center, radius: radius),
                        identifier: region.identifier
                    )
                    MunimLocationCore.onMain { completion(.success(())) }
                }
            } else {
                let circular = CLCircularRegion(center: center, radius: radius, identifier: region.identifier)
                circular.notifyOnEntry = true
                circular.notifyOnExit = true
                self.monitoringManager.startMonitoring(for: circular)
                completion(.success(()))
            }
        }
    }

    func removeRegion(_ identifier: String, _ completion: (() -> Void)? = nil) {
        MunimLocationCore.onMain {
            var regions = self.storedRegions()
            regions.removeValue(forKey: identifier)
            self.saveRegions(regions)
            self.dwellTimers.removeValue(forKey: identifier)?.cancel()
            self.expiryTimers.removeValue(forKey: identifier)?.cancel()
            if #available(iOS 17.0, *) {
                let engine = self.engine()
                Task {
                    await engine.remove(identifier)
                    MunimLocationCore.onMain { completion?() }
                }
            } else {
                for region in self.monitoringManager.monitoredRegions where region.identifier == identifier {
                    self.monitoringManager.stopMonitoring(for: region)
                }
                completion?()
            }
        }
    }

    func removeAllGeofences(_ completion: @escaping () -> Void) {
        MunimLocationCore.onMain {
            let identifiers = self.storedRegions().filter { ($0.value["kind"] as? String) != "beacon" }.map { $0.key }
            guard !identifiers.isEmpty else {
                completion()
                return
            }
            let group = DispatchGroup()
            for identifier in identifiers {
                group.enter()
                self.removeRegion(identifier) { group.leave() }
            }
            group.notify(queue: .main, execute: completion)
        }
    }

    func monitoredGeofences(_ completion: @escaping ([GeofenceRegion]) -> Void) {
        MunimLocationCore.onMain {
            completion(self.storedRegions().values
                .filter { ($0["kind"] as? String) != "beacon" }
                .compactMap { MunimLocationConvert.geofence($0) }
                .sorted { $0.identifier < $1.identifier })
        }
    }

    func geofenceState(_ identifier: String, _ completion: @escaping (GeofenceState) -> Void) {
        MunimLocationCore.onMain {
            guard self.storedRegions()[identifier] != nil else {
                completion(.unknown)
                return
            }
            if #available(iOS 17.0, *) {
                let engine = self.engine()
                Task {
                    let state = await engine.state(identifier)
                    MunimLocationCore.onMain { completion(MunimLocationCore.geofenceState(state)) }
                }
                return
            }
            guard let region = self.monitoringManager.monitoredRegions.first(where: { $0.identifier == identifier }) else {
                completion(.unknown)
                return
            }
            self.stateWaiters[identifier, default: []].append(completion)
            self.monitoringManager.requestState(for: region)
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
                guard let waiters = self.stateWaiters.removeValue(forKey: identifier) else { return }
                let last = self.storedRegions()[identifier]?["lastState"] as? String ?? "unknown"
                waiters.forEach { $0(GeofenceState(fromString: last) ?? .unknown) }
            }
        }
    }

    private static func geofenceState(_ state: CLRegionState) -> GeofenceState {
        switch state {
        case .inside: return .inside
        case .outside: return .outside
        case .unknown: return .unknown
        @unknown default: return .unknown
        }
    }

    @available(iOS 17.0, *)
    fileprivate static func geofenceState(_ state: CLMonitor.Event.State) -> GeofenceState {
        switch state {
        case .satisfied: return .inside
        case .unsatisfied: return .outside
        default: return .unknown
        }
    }

    private func scheduleExpiry(identifier: String, meta: [String: Any]) {
        guard let expiresAt = meta["expiresAt"] as? Double else { return }
        expiryTimers.removeValue(forKey: identifier)?.cancel()
        let delay = max(0, (expiresAt - MunimLocationConvert.nowMs()) / 1000)
        let item = DispatchWorkItem { [weak self] in
            self?.removeRegion(identifier)
            MunimLocationEvents.shared.emit("geofenceExpired", ["identifier": identifier], persist: true)
        }
        expiryTimers[identifier] = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    /// Shared state handler for CLMonitor events and legacy didDetermineState.
    fileprivate func handleRegionState(identifier: String, state: GeofenceState, extra: [String: Any]) {
        guard let meta = storedRegions()[identifier] else { return }
        let isBeacon = (meta["kind"] as? String) == "beacon"
        var stateBody: [String: Any] = ["identifier": identifier, "state": state.stringValue, "kind": isBeacon ? "beacon" : "circle"]
        extra.forEach { stateBody[$0.key] = $0.value }
        MunimLocationEvents.shared.emit("geofenceState", stateBody)

        if let waiters = stateWaiters.removeValue(forKey: identifier) {
            waiters.forEach { $0(state) }
        }
        guard state != .unknown else { return }

        let first = !(meta["initialized"] as? Bool ?? false)
        let previous = meta["lastState"] as? String
        updateRegion(identifier) {
            $0["initialized"] = true
            $0["lastState"] = state.stringValue
        }
        if previous == state.stringValue { return }

        if state == .inside {
            let notify = first
                ? (meta["notifyOnStartIfInside"] as? Bool ?? false)
                : (meta["notifyOnEntry"] as? Bool ?? true)
            if notify {
                emitTransition(identifier: identifier, transition: "enter", isBeacon: isBeacon)
            }
            if meta["notifyOnDwell"] as? Bool ?? false {
                let delay = max(0, (meta["loiteringDelayMs"] as? Double ?? 0) / 1000)
                dwellTimers.removeValue(forKey: identifier)?.cancel()
                let item = DispatchWorkItem { [weak self] in
                    self?.dwellTimers.removeValue(forKey: identifier)
                    self?.emitTransition(identifier: identifier, transition: "dwell", isBeacon: isBeacon)
                }
                dwellTimers[identifier] = item
                DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
            }
        } else if state == .outside {
            dwellTimers.removeValue(forKey: identifier)?.cancel()
            if !first, meta["notifyOnExit"] as? Bool ?? true {
                emitTransition(identifier: identifier, transition: "exit", isBeacon: isBeacon)
            }
        }
    }

    private func emitTransition(identifier: String, transition: String, isBeacon: Bool) {
        var body: [String: Any] = [
            "identifier": identifier,
            "transition": transition,
            "kind": isBeacon ? "beacon" : "circle",
            "timestamp": MunimLocationConvert.nowMs(),
        ]
        if let location = authManager.location {
            body["location"] = MunimLocationConvert.dict(location)
        }
        MunimLocationEvents.shared.emit("geofenceTransition", body, persist: true)
    }

    // MARK: - Beacons

    func startBeaconRanging(_ constraint: BeaconConstraint) {
        MunimLocationCore.onMain {
            guard #available(iOS 13.0, *), let uuid = UUID(uuidString: constraint.uuid) else {
                MunimLocationEvents.shared.emit("beaconRangingError", [
                    "identifier": constraint.identifier, "code": "E_INVALID_UUID", "message": "Invalid beacon UUID",
                ])
                return
            }
            let identity = MunimLocationCore.beaconConstraint(uuid: uuid, major: constraint.major, minor: constraint.minor)
            self.rangingConstraints[constraint.identifier] = identity
            self.monitoringManager.startRangingBeacons(satisfying: identity)
        }
    }

    func stopBeaconRanging(_ identifier: String) {
        MunimLocationCore.onMain {
            guard #available(iOS 13.0, *),
                  let identity = self.rangingConstraints.removeValue(forKey: identifier) as? CLBeaconIdentityConstraint else {
                return
            }
            self.monitoringManager.stopRangingBeacons(satisfying: identity)
        }
    }

    @available(iOS 13.0, *)
    private static func beaconConstraint(uuid: UUID, major: Double, minor: Double) -> CLBeaconIdentityConstraint {
        if major >= 0, minor >= 0 {
            return CLBeaconIdentityConstraint(uuid: uuid, major: CLBeaconMajorValue(major), minor: CLBeaconMinorValue(minor))
        }
        if major >= 0 {
            return CLBeaconIdentityConstraint(uuid: uuid, major: CLBeaconMajorValue(major))
        }
        return CLBeaconIdentityConstraint(uuid: uuid)
    }

    func startBeaconMonitoring(_ constraint: BeaconConstraint, notifyOnDisplay: Bool, _ completion: @escaping (Result<Void, Error>) -> Void) {
        MunimLocationCore.onMain {
            guard let uuid = UUID(uuidString: constraint.uuid) else {
                completion(.failure(MunimLocationError.make("E_INVALID_UUID", "Invalid beacon UUID")))
                return
            }
            guard CLLocationManager.isMonitoringAvailable(for: CLBeaconRegion.self) else {
                completion(.failure(MunimLocationError.unsupported("Beacon monitoring")))
                return
            }
            var regions = self.storedRegions()
            if regions[constraint.identifier] == nil, regions.count >= 20 {
                completion(.failure(MunimLocationError.make("E_GEOFENCE_LIMIT", "iOS monitors at most 20 regions per app")))
                return
            }
            regions[constraint.identifier] = [
                "identifier": constraint.identifier,
                "kind": "beacon",
                "uuid": constraint.uuid,
                "major": constraint.major,
                "minor": constraint.minor,
                "notifyOnEntry": true,
                "notifyOnExit": true,
                "notifyOnStartIfInside": true,
            ]
            self.saveRegions(regions)
            if #available(iOS 17.0, *) {
                let condition: CLMonitor.BeaconIdentityCondition
                if constraint.major >= 0, constraint.minor >= 0 {
                    condition = CLMonitor.BeaconIdentityCondition(uuid: uuid, major: CLBeaconMajorValue(constraint.major), minor: CLBeaconMinorValue(constraint.minor))
                } else if constraint.major >= 0 {
                    condition = CLMonitor.BeaconIdentityCondition(uuid: uuid, major: CLBeaconMajorValue(constraint.major))
                } else {
                    condition = CLMonitor.BeaconIdentityCondition(uuid: uuid)
                }
                let engine = self.engine()
                Task {
                    await engine.add(condition, identifier: constraint.identifier)
                    MunimLocationCore.onMain { completion(.success(())) }
                }
            } else if #available(iOS 13.0, *) {
                let identity = MunimLocationCore.beaconConstraint(uuid: uuid, major: constraint.major, minor: constraint.minor)
                let region = CLBeaconRegion(beaconIdentityConstraint: identity, identifier: constraint.identifier)
                region.notifyEntryStateOnDisplay = notifyOnDisplay
                region.notifyOnEntry = true
                region.notifyOnExit = true
                self.monitoringManager.startMonitoring(for: region)
                completion(.success(()))
            } else {
                completion(.failure(MunimLocationError.unsupported("Beacon monitoring")))
            }
        }
    }

    // MARK: - Heading

    func startHeading(_ options: HeadingOptions) {
        MunimLocationCore.onMain {
            self.headingRequestedByUser = true
            self.configureHeading(options)
            self.headingManager.startUpdatingHeading()
        }
    }

    private func configureHeading(_ options: HeadingOptions) {
        headingManager.headingFilter = options.headingFilter > 0 ? options.headingFilter : kCLHeadingFilterNone
        headingManager.headingOrientation = MunimLocationConvert.headingOrientation(options.orientation)
        showsHeadingCalibration = options.showsCalibrationDisplay
    }

    func stopHeading() {
        MunimLocationCore.onMain {
            self.headingRequestedByUser = false
            if self.headingWaiters.isEmpty {
                self.headingManager.stopUpdatingHeading()
            }
        }
    }

    func currentHeading(timeoutMs: Double, _ completion: @escaping (Result<Heading, Error>) -> Void) {
        MunimLocationCore.onMain {
            guard CLLocationManager.headingAvailable() else {
                completion(.failure(MunimLocationError.unsupported("Heading")))
                return
            }
            if self.headingRequestedByUser, let last = self.lastHeading,
               MunimLocationConvert.nowMs() - last.timestamp < 1000 {
                completion(.success(last))
                return
            }
            var finished = false
            self.headingWaiters.append { heading in
                guard !finished else { return }
                finished = true
                completion(.success(heading))
            }
            self.headingManager.startUpdatingHeading()
            DispatchQueue.main.asyncAfter(deadline: .now() + max(0.5, timeoutMs / 1000)) {
                guard !finished else { return }
                finished = true
                self.headingWaiters.removeAll()
                if !self.headingRequestedByUser {
                    self.headingManager.stopUpdatingHeading()
                }
                completion(.failure(MunimLocationError.timeout))
            }
        }
    }

    func dismissHeadingCalibration() {
        MunimLocationCore.onMain {
            self.headingManager.dismissHeadingCalibrationDisplay()
        }
    }

    // MARK: - Altitude

    func startAltitude(absolute: Bool) {
        MunimLocationCore.onMain {
            switch CMAltimeter.authorizationStatus() {
            case .denied, .restricted:
                MunimLocationEvents.shared.emit("altitudeError", [
                    "code": "E_MOTION_PERMISSION_DENIED",
                    "message": "Motion & Fitness access is off for this app (needs NSMotionUsageDescription)",
                ])
                return
            case .notDetermined:
                // CoreMotion shows the Motion & Fitness prompt; samples start once allowed.
                MunimLocationEvents.shared.emit("altitudeError", [
                    "code": "E_MOTION_PERMISSION_PENDING",
                    "message": "Waiting for the Motion & Fitness permission prompt",
                ])
            default:
                break
            }
            if CMAltimeter.isRelativeAltitudeAvailable() {
                self.altimeter.startRelativeAltitudeUpdates(to: .main) { data, error in
                    if let error {
                        MunimLocationEvents.shared.emit("altitudeError", MunimLocationConvert.errorDict(error))
                        return
                    }
                    guard let data else { return }
                    MunimLocationEvents.shared.emit("altitude", [
                        "kind": "relative",
                        "relativeAltitude": data.relativeAltitude.doubleValue,
                        "pressure": data.pressure.doubleValue,
                        "timestamp": MunimLocationConvert.nowMs(),
                    ])
                }
            } else {
                MunimLocationEvents.shared.emit("altitudeError", [
                    "code": "E_UNSUPPORTED", "message": "This device has no barometer",
                ])
            }
            if absolute, #available(iOS 15.0, *), CMAltimeter.isAbsoluteAltitudeAvailable() {
                self.altimeter.startAbsoluteAltitudeUpdates(to: .main) { data, error in
                    if let error {
                        MunimLocationEvents.shared.emit("altitudeError", MunimLocationConvert.errorDict(error))
                        return
                    }
                    guard let data else { return }
                    MunimLocationEvents.shared.emit("altitude", [
                        "kind": "absolute",
                        "altitude": data.altitude,
                        "accuracy": data.accuracy,
                        "precision": data.precision,
                        "timestamp": MunimLocationConvert.nowMs(),
                    ])
                }
            }
        }
    }

    func stopAltitude() {
        MunimLocationCore.onMain {
            self.altimeter.stopRelativeAltitudeUpdates()
            if #available(iOS 15.0, *) {
                self.altimeter.stopAbsoluteAltitudeUpdates()
            }
        }
    }

    // MARK: - Capabilities

    func capabilities(_ completion: @escaping (LocationCapabilities) -> Void) {
        MunimLocationCore.servicesEnabled { enabled in
            MunimLocationCore.onMain {
                var liveUpdates = false
                var backgroundActivity = false
                var serviceSession = false
                var absoluteAltitude = false
                var temporaryFullAccuracy = false
                if #available(iOS 14.0, *) { temporaryFullAccuracy = true }
                if #available(iOS 15.0, *) { absoluteAltitude = CMAltimeter.isAbsoluteAltitudeAvailable() }
                if #available(iOS 17.0, *) {
                    liveUpdates = true
                    backgroundActivity = true
                }
                if #available(iOS 18.0, *) { serviceSession = true }
                completion(LocationCapabilities(
                    platform: "ios",
                    osVersion: UIDevice.current.systemVersion,
                    locationServicesEnabled: enabled,
                    headingAvailable: CLLocationManager.headingAvailable(),
                    significantChangeAvailable: CLLocationManager.significantLocationChangeMonitoringAvailable(),
                    visitsAvailable: true,
                    regionMonitoringAvailable: CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self),
                    maxMonitoredRegions: 20,
                    geofencingEngine: self.usesMonitor ? "clmonitor" : "regionMonitoring",
                    beaconRangingAvailable: CLLocationManager.isRangingAvailable(),
                    beaconMonitoringAvailable: CLLocationManager.isMonitoringAvailable(for: CLBeaconRegion.self),
                    liveUpdatesAvailable: liveUpdates,
                    serviceSessionAvailable: serviceSession,
                    backgroundActivitySessionAvailable: backgroundActivity,
                    temporaryFullAccuracyAvailable: temporaryFullAccuracy,
                    altimeterAvailable: CMAltimeter.isRelativeAltitudeAvailable(),
                    absoluteAltitudeAvailable: absoluteAltitude,
                    gnssStatusAvailable: false,
                    nmeaAvailable: false,
                    gnssMeasurementsAvailable: false,
                    gnssNavigationMessagesAvailable: false,
                    fusedLocationAvailable: false,
                    fusedOrientationAvailable: false,
                    geocoderAvailable: true,
                    mockLocationAvailable: false,
                    backgroundLocationModeEnabled: self.backgroundModeEnabled
                ))
            }
        }
    }

    // MARK: - CLLocationManagerDelegate (shared managers)

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard manager === authManager else { return }
        handleAuthorizationChange()
    }

    // iOS 13 and earlier.
    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        if #available(iOS 14.0, *) { return }
        guard manager === authManager else { return }
        handleAuthorizationChange()
    }

    private var lastReportedAuthorization: String?

    private func handleAuthorizationChange() {
        permissionStatus { status in
            let key = "\(status.status.stringValue)|\(status.accuracy.stringValue)|\(status.locationServicesEnabled)"
            if self.lastReportedAuthorization != nil, self.lastReportedAuthorization != key {
                MunimLocationEvents.shared.emit("authorizationChanged", MunimLocationCore.permissionDict(status))
                MunimLocationEvents.shared.emit("providerChanged", [
                    "locationServicesEnabled": status.locationServicesEnabled,
                    "gpsEnabled": status.locationServicesEnabled,
                    "networkEnabled": status.locationServicesEnabled,
                ])
            }
            self.lastReportedAuthorization = key
            if status.status != .notdetermined {
                self.permissionWaiters.forEach { $0.finish() }
            }
        }
    }

    static func permissionDict(_ status: PermissionStatus) -> [String: Any] {
        [
            "status": status.status.stringValue,
            "accuracy": status.accuracy.stringValue,
            "foreground": status.foreground,
            "background": status.background,
            "precise": status.precise,
            "canAskAgain": status.canAskAgain,
            "locationServicesEnabled": status.locationServicesEnabled,
        ]
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let body: [String: Any] = ["locations": locations.map { MunimLocationConvert.dict($0) }]
        if manager === backgroundManager {
            MunimLocationEvents.shared.emit("backgroundLocation", body, persist: true)
        } else if manager === monitoringManager {
            MunimLocationEvents.shared.emit("significantLocationChange", body, persist: true)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as NSError).domain == kCLErrorDomain, (error as NSError).code == CLError.locationUnknown.rawValue {
            return
        }
        var body = MunimLocationConvert.errorDict(error)
        if manager === backgroundManager {
            body["source"] = "background"
        } else if manager === monitoringManager {
            body["source"] = "monitoring"
        } else if manager === headingManager {
            body["source"] = "heading"
        } else {
            body["source"] = "authorization"
        }
        MunimLocationEvents.shared.emit("locationError", body)
    }

    func locationManagerDidPauseLocationUpdates(_ manager: CLLocationManager) {
        if manager === backgroundManager {
            MunimLocationEvents.shared.emit("locationUpdatesPaused", ["source": "background"], persist: true)
        }
    }

    func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        if manager === backgroundManager {
            MunimLocationEvents.shared.emit("locationUpdatesResumed", ["source": "background"], persist: true)
        }
    }

    func locationManager(_ manager: CLLocationManager, didVisit visit: CLVisit) {
        MunimLocationEvents.shared.emit("visit", MunimLocationConvert.visit(visit), persist: true)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard newHeading.headingAccuracy >= 0 || newHeading.magneticHeading >= 0 else { return }
        let heading = MunimLocationConvert.heading(newHeading)
        lastHeading = heading
        if !headingWaiters.isEmpty {
            let waiters = headingWaiters
            headingWaiters.removeAll()
            waiters.forEach { $0(heading) }
            if !headingRequestedByUser {
                headingManager.stopUpdatingHeading()
            }
        }
        if headingRequestedByUser {
            MunimLocationEvents.shared.emit("heading", MunimLocationConvert.dict(heading))
        }
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        if headingRequestedByUser {
            MunimLocationEvents.shared.emit("headingCalibrationNeeded", ["timestamp": MunimLocationConvert.nowMs()])
        }
        return showsHeadingCalibration
    }

    // Legacy region monitoring (iOS 16 and earlier).
    func locationManager(_ manager: CLLocationManager, didStartMonitoringFor region: CLRegion) {
        manager.requestState(for: region)
    }

    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        handleRegionState(identifier: region.identifier, state: MunimLocationCore.geofenceState(state), extra: [:])
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        var body = MunimLocationConvert.errorDict(error)
        body["identifier"] = region?.identifier
        MunimLocationEvents.shared.emit("geofenceError", body, persist: true)
    }

    @available(iOS 13.0, *)
    func locationManager(_ manager: CLLocationManager, didRange beacons: [CLBeacon], satisfying beaconConstraint: CLBeaconIdentityConstraint) {
        for (identifier, constraint) in rangingConstraints {
            guard let constraint = constraint as? CLBeaconIdentityConstraint, constraint == beaconConstraint else { continue }
            MunimLocationEvents.shared.emit("beaconsRanged", [
                "identifier": identifier,
                "beacons": beacons.map { MunimLocationConvert.beacon($0) },
            ])
        }
    }

    @available(iOS 13.0, *)
    func locationManager(_ manager: CLLocationManager, didFailRangingFor beaconConstraint: CLBeaconIdentityConstraint, error: Error) {
        for (identifier, constraint) in rangingConstraints {
            guard let constraint = constraint as? CLBeaconIdentityConstraint, constraint == beaconConstraint else { continue }
            var body = MunimLocationConvert.errorDict(error)
            body["identifier"] = identifier
            MunimLocationEvents.shared.emit("beaconRangingError", body)
        }
    }
}

// MARK: - Prompt waiter

/// Resolves a permission request once iOS reports a decision, or once the
/// app becomes active again after a prompt, or after a short grace period
/// when no prompt appeared at all.
private final class PromptWaiter {
    private let onFinish: () -> Void
    private(set) var finished = false
    private var resignedActive = false
    private var observers: [NSObjectProtocol] = []

    init(onFinish: @escaping () -> Void) {
        self.onFinish = onFinish
    }

    func arm() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.resignedActive = true
        })
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, self.resignedActive else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { self.finish() }
        })
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self, !self.resignedActive else { return }
            self.finish()
        }
        // Never hang forever.
        DispatchQueue.main.asyncAfter(deadline: .now() + 120) { [weak self] in
            self?.finish()
        }
    }

    func finish() {
        guard !finished else { return }
        finished = true
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        onFinish()
    }
}

// MARK: - CLMonitor engine (iOS 17+)

@available(iOS 17.0, *)
private actor MonitorEngine {
    private static let name = "MunimLocationMonitor"
    private var current: CLMonitor?
    private var starting: Task<CLMonitor, Never>?
    private var eventsTask: Task<Void, Never>?
    private let handler: @Sendable (String, GeofenceState, [String: Any]) -> Void

    init(handler: @escaping @Sendable (String, GeofenceState, [String: Any]) -> Void) {
        self.handler = handler
    }

    func monitor() async -> CLMonitor {
        if let current { return current }
        if let starting { return await starting.value }
        let task = Task { await CLMonitor(MonitorEngine.name) }
        starting = task
        let created = await task.value
        current = created
        startEvents(created)
        return created
    }

    private func startEvents(_ monitor: CLMonitor) {
        let handler = self.handler
        eventsTask = Task {
            do {
                for try await event in await monitor.events {
                    var extra: [String: Any] = ["timestamp": event.date.timeIntervalSince1970 * 1000]
                    if #available(iOS 18.0, *) {
                        extra["authorizationDenied"] = event.authorizationDenied
                        extra["insufficientlyInUse"] = event.insufficientlyInUse
                        extra["accuracyLimited"] = event.accuracyLimited
                        extra["conditionLimitExceeded"] = event.conditionLimitExceeded
                        extra["conditionUnsupported"] = event.conditionUnsupported
                        extra["persistenceUnavailable"] = event.persistenceUnavailable
                        extra["serviceSessionRequired"] = event.serviceSessionRequired
                    }
                    handler(event.identifier, MunimLocationCore.geofenceState(event.state), extra)
                }
            } catch {
                MunimLocationEvents.shared.emit("geofenceError", MunimLocationConvert.errorDict(error))
            }
        }
    }

    func add(_ condition: any CLCondition, identifier: String) async {
        let monitor = await monitor()
        await monitor.add(condition, identifier: identifier, assuming: .unknown)
    }

    func remove(_ identifier: String) async {
        let monitor = await monitor()
        await monitor.remove(identifier)
    }

    func state(_ identifier: String) async -> CLMonitor.Event.State {
        let monitor = await monitor()
        return await monitor.record(for: identifier)?.lastEvent.state ?? .unknown
    }
}

// MARK: - Watch sessions

private final class WatchSession: NSObject, CLLocationManagerDelegate {
    let id: Int
    let options: WatchOptions
    weak var core: MunimLocationCore?
    private var manager: CLLocationManager?
    private var liveTask: Task<Void, Never>?
    private var delivered = 0
    private var lastDelivered: CLLocation?
    private var lastDiagnostic: String?

    init(id: Int, options: WatchOptions, core: MunimLocationCore) {
        self.id = id
        self.options = options
        self.core = core
    }

    func start() {
        if options.useLiveUpdates, #available(iOS 17.0, *) {
            startLiveUpdates()
            return
        }
        let manager = CLLocationManager()
        manager.delegate = self
        manager.desiredAccuracy = MunimLocationConvert.desiredAccuracy(options.accuracy)
        manager.distanceFilter = options.distanceFilter > 0 ? options.distanceFilter : kCLDistanceFilterNone
        manager.activityType = MunimLocationConvert.activityType(options.activityType)
        manager.pausesLocationUpdatesAutomatically = options.pausesLocationUpdatesAutomatically
        if options.allowsBackgroundLocationUpdates {
            if core?.backgroundModeEnabled == true {
                manager.allowsBackgroundLocationUpdates = true
                manager.showsBackgroundLocationIndicator = options.showsBackgroundLocationIndicator
            } else {
                var body = MunimLocationConvert.errorDict(MunimLocationError.backgroundModeMissing)
                body["watchId"] = id
                body["code"] = "E_BACKGROUND_MODE_MISSING"
                MunimLocationEvents.shared.emit("locationError", body)
            }
        }
        self.manager = manager
        manager.startUpdatingLocation()
    }

    @available(iOS 17.0, *)
    private func startLiveUpdates() {
        let configuration = MunimLocationConvert.liveConfiguration(options.activityType)
        let id = self.id
        liveTask = Task { [weak self] in
            do {
                for try await update in CLLocationUpdate.liveUpdates(configuration) {
                    if Task.isCancelled { break }
                    var diagnostic: [String: Any] = ["watchId": id]
                    if #available(iOS 18.0, *) {
                        diagnostic["stationary"] = update.stationary
                        diagnostic["insufficientlyInUse"] = update.insufficientlyInUse
                        diagnostic["locationUnavailable"] = update.locationUnavailable
                        diagnostic["accuracyLimited"] = update.accuracyLimited
                        diagnostic["authorizationDenied"] = update.authorizationDenied
                        diagnostic["authorizationDeniedGlobally"] = update.authorizationDeniedGlobally
                        diagnostic["authorizationRestricted"] = update.authorizationRestricted
                        diagnostic["authorizationRequestInProgress"] = update.authorizationRequestInProgress
                        diagnostic["serviceSessionRequired"] = update.serviceSessionRequired
                    } else {
                        diagnostic["stationary"] = update.isStationary
                    }
                    let location = update.location
                    let snapshot = diagnostic
                    DispatchQueue.main.async {
                        self?.handleLive(location: location, diagnostic: snapshot)
                    }
                }
            } catch {
                var body = MunimLocationConvert.errorDict(error)
                body["watchId"] = id
                MunimLocationEvents.shared.emit("locationError", body)
            }
        }
    }

    private func handleLive(location: CLLocation?, diagnostic: [String: Any]) {
        let key = diagnostic
            .filter { $0.key != "watchId" }
            .sorted { $0.key < $1.key }
            .map { "\($0.key)=\($0.value)" }
            .joined(separator: ",")
        if key != lastDiagnostic {
            lastDiagnostic = key
            MunimLocationEvents.shared.emit("liveUpdateDiagnostic", diagnostic)
        }
        guard let location else { return }
        if options.distanceFilter > 0, let last = lastDelivered,
           location.distance(from: last) < options.distanceFilter {
            return
        }
        deliver([location])
    }

    private func deliver(_ locations: [CLLocation]) {
        guard !locations.isEmpty else { return }
        lastDelivered = locations.last
        delivered += locations.count
        MunimLocationEvents.shared.emit("location", [
            "watchId": id,
            "locations": locations.map { MunimLocationConvert.dict($0) },
        ])
        if options.maxUpdates > 0, delivered >= Int(options.maxUpdates) {
            core?.clearWatch(id)
        }
    }

    func stop() {
        liveTask?.cancel()
        liveTask = nil
        manager?.stopUpdatingLocation()
        manager?.delegate = nil
        manager = nil
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        deliver(locations)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let nsError = error as NSError
        if nsError.domain == kCLErrorDomain, nsError.code == CLError.locationUnknown.rawValue { return }
        var body = MunimLocationConvert.errorDict(error)
        body["watchId"] = id
        MunimLocationEvents.shared.emit("locationError", body)
    }

    func locationManagerDidPauseLocationUpdates(_ manager: CLLocationManager) {
        MunimLocationEvents.shared.emit("locationUpdatesPaused", ["watchId": id])
    }

    func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        MunimLocationEvents.shared.emit("locationUpdatesResumed", ["watchId": id])
    }
}

// MARK: - One-shot requests

private final class OneShotRequest: NSObject, CLLocationManagerDelegate {
    private let options: CurrentPositionOptions
    private let manager = CLLocationManager()
    private var best: CLLocation?
    private var completion: ((Result<Location, Error>) -> Void)?
    private let startedAt = Date()

    init(options: CurrentPositionOptions) {
        self.options = options
        super.init()
    }

    func start(_ completion: @escaping (Result<Location, Error>) -> Void) {
        self.completion = completion
        manager.delegate = self
        manager.desiredAccuracy = MunimLocationConvert.desiredAccuracy(options.accuracy)
        manager.startUpdatingLocation()
        let timeout = options.timeoutMs > 0 ? options.timeoutMs / 1000 : 30
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
            guard let self else { return }
            if let best = self.best {
                self.finish(.success(MunimLocationConvert.location(best)))
            } else {
                self.finish(.failure(MunimLocationError.timeout))
            }
        }
    }

    private func finish(_ result: Result<Location, Error>) {
        guard let completion else { return }
        self.completion = nil
        manager.stopUpdatingLocation()
        manager.delegate = nil
        completion(result)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let target = MunimLocationConvert.acceptableAccuracy(options.accuracy)
        let maxAge = max(options.maximumAgeMs / 1000, 2)
        for location in locations where location.horizontalAccuracy >= 0 {
            // Ignore stale cached fixes the manager replays at start.
            if location.timestamp < startedAt.addingTimeInterval(-maxAge) { continue }
            if best == nil || location.horizontalAccuracy < best!.horizontalAccuracy {
                best = location
            }
            if location.horizontalAccuracy <= target {
                finish(.success(MunimLocationConvert.location(location)))
                return
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let nsError = error as NSError
        if nsError.domain == kCLErrorDomain, nsError.code == CLError.denied.rawValue {
            finish(.failure(MunimLocationError.permissionDenied))
        }
    }
}
