//
//  HybridMunimLocation.swift
//  munim-location
//
//  Nitro entry point. Every call delegates to the process-wide
//  MunimLocationCore so state survives JavaScript reloads.
//

import CoreLocation
import Foundation
import NitroModules

final class HybridMunimLocation: HybridMunimLocationSpec {
    private let core = MunimLocationCore.shared

    private static func promise<T>(_ body: (@escaping (Result<T, Error>) -> Void) -> Void) -> Promise<T> {
        let promise = Promise<T>()
        body { result in
            switch result {
            case .success(let value): promise.resolve(withResult: value)
            case .failure(let error): promise.reject(withError: error)
            }
        }
        return promise
    }

    private static func resolved<T>(_ body: (@escaping (T) -> Void) -> Void) -> Promise<T> {
        let promise = Promise<T>()
        body { promise.resolve(withResult: $0) }
        return promise
    }

    private static func unsupported<T>(_ what: String) -> Promise<T> {
        let promise = Promise<T>()
        promise.reject(withError: MunimLocationError.unsupported(what))
        return promise
    }

    // MARK: - Events

    func setEventListener(listener: @escaping (_ name: String, _ payload: String) -> Void) throws {
        MunimLocationEvents.shared.setListener(listener)
        core.observeAuthorization()
    }

    func removeEventListener() throws {
        MunimLocationEvents.shared.removeListener()
    }

    func getPendingBackgroundEvents() throws -> String {
        MunimLocationEvents.shared.pendingJSON()
    }

    func clearPendingBackgroundEvents() throws {
        MunimLocationEvents.shared.clearPending()
    }

    // MARK: - Permissions

    func getPermissionStatus() throws -> Promise<PermissionStatus> {
        Self.resolved { core.permissionStatus($0) }
    }

    func requestForegroundPermission(precise: Bool) throws -> Promise<PermissionStatus> {
        Self.resolved { core.requestForeground($0) }
    }

    func requestBackgroundPermission() throws -> Promise<PermissionStatus> {
        Self.resolved { core.requestBackground($0) }
    }

    func requestTemporaryFullAccuracy(purposeKey: String) throws -> Promise<AccuracyAuthorization> {
        Self.promise { core.requestTemporaryFullAccuracy(purposeKey: purposeKey, $0) }
    }

    func shouldShowRationale(permission: AndroidLocationPermission) throws -> Bool {
        false
    }

    func openAppSettings() throws -> Promise<Bool> {
        Self.resolved { core.openSettings($0) }
    }

    func openLocationSettings() throws -> Promise<Bool> {
        // iOS has no public deep link to Settings > Privacy > Location Services.
        Self.resolved { core.openSettings($0) }
    }

    // MARK: - Services

    func isLocationServicesEnabled() throws -> Promise<Bool> {
        Self.resolved { MunimLocationCore.servicesEnabled($0) }
    }

    func getProviderStatus() throws -> Promise<ProviderStatus> {
        Self.resolved { completion in
            MunimLocationCore.servicesEnabled { enabled in
                completion(ProviderStatus(
                    locationServicesEnabled: enabled,
                    gpsEnabled: enabled,
                    networkEnabled: enabled,
                    passiveEnabled: enabled,
                    fusedEnabled: false,
                    airplaneModeOn: false,
                    providers: ["corelocation"]
                ))
            }
        }
    }

    func checkLocationSettings(priority: LocationPriority, needBle: Bool) throws -> Promise<LocationSettingsResult> {
        Self.resolved { completion in
            core.permissionStatus { status in
                let usable = status.locationServicesEnabled && status.foreground
                completion(LocationSettingsResult(
                    satisfied: usable,
                    resolvable: false,
                    locationPresent: true,
                    locationUsable: usable,
                    gpsPresent: true,
                    gpsUsable: usable,
                    networkLocationPresent: true,
                    networkLocationUsable: usable,
                    blePresent: true,
                    bleUsable: usable,
                    statusCode: usable ? 0 : 6,
                    message: usable ? "" : (status.locationServicesEnabled
                        ? "Location permission has not been granted"
                        : "Location services are turned off")
                ))
            }
        }
    }

    func requestLocationSettingsResolution(priority: LocationPriority, needBle: Bool) throws -> Promise<Bool> {
        // iOS shows its own "turn on Location Services" alert when an app
        // starts location updates with services off; there is no resolution API.
        Self.resolved { completion in
            core.permissionStatus { completion($0.locationServicesEnabled && $0.foreground) }
        }
    }

    func startServiceSession(authorization: ServiceSessionAuthorization, fullAccuracyPurposeKey: String) throws -> String {
        core.startServiceSession(authorization: authorization, purposeKey: fullAccuracyPurposeKey)
    }

    func stopServiceSession(sessionId: String) throws {
        core.stopSession(sessionId)
    }

    func startBackgroundActivitySession() throws -> String {
        core.startBackgroundActivitySession()
    }

    func stopBackgroundActivitySession(sessionId: String) throws {
        core.stopSession(sessionId)
    }

    // MARK: - Positions

    func getCurrentPosition(options: CurrentPositionOptions) throws -> Promise<Location> {
        Self.promise { core.currentPosition(options, $0) }
    }

    func getLastKnownPosition(options: LastKnownPositionOptions) throws -> Promise<Location?> {
        Self.resolved { core.lastKnownPosition(options, $0) }
    }

    func watchPosition(options: WatchOptions) throws -> Double {
        Double(core.watch(options))
    }

    func clearWatch(watchId: Double) throws {
        core.clearWatch(Int(watchId))
    }

    func clearAllWatches() throws {
        core.clearAllWatches()
    }

    // MARK: - Background

    func startBackgroundUpdates(options: WatchOptions, android: AndroidBackgroundOptions) throws -> Promise<Bool> {
        Self.promise { core.startBackground(options, $0) }
    }

    func stopBackgroundUpdates() throws -> Promise<Void> {
        core.stopBackground()
        return Promise.resolved(withResult: ())
    }

    func startSignificantLocationChanges() throws -> Promise<Bool> {
        Self.promise { core.startSignificantChanges($0) }
    }

    func stopSignificantLocationChanges() throws {
        core.stopSignificantChanges()
    }

    func startVisitMonitoring() throws -> Promise<Bool> {
        Self.promise { core.startVisits($0) }
    }

    func stopVisitMonitoring() throws {
        core.stopVisits()
    }

    func getBackgroundStatus() throws -> BackgroundStatus {
        core.backgroundStatus()
    }

    // MARK: - Geofencing

    func addGeofence(region: GeofenceRegion) throws -> Promise<Void> {
        Self.promise { core.addGeofence(region, $0) }
    }

    func removeGeofence(identifier: String) throws -> Promise<Void> {
        Self.resolved { completion in core.removeRegion(identifier) { completion(()) } }
    }

    func removeAllGeofences() throws -> Promise<Void> {
        Self.resolved { completion in core.removeAllGeofences { completion(()) } }
    }

    func getMonitoredGeofences() throws -> Promise<[GeofenceRegion]> {
        Self.resolved { core.monitoredGeofences($0) }
    }

    func requestGeofenceState(identifier: String) throws -> Promise<GeofenceState> {
        Self.resolved { core.geofenceState(identifier, $0) }
    }

    func getMaxMonitoredGeofences() throws -> Double {
        20
    }

    // MARK: - Beacons

    func startBeaconRanging(constraint: BeaconConstraint) throws {
        core.startBeaconRanging(constraint)
    }

    func stopBeaconRanging(identifier: String) throws {
        core.stopBeaconRanging(identifier)
    }

    func startBeaconMonitoring(constraint: BeaconConstraint, notifyEntryStateOnDisplay: Bool) throws -> Promise<Void> {
        Self.promise { core.startBeaconMonitoring(constraint, notifyOnDisplay: notifyEntryStateOnDisplay, $0) }
    }

    func stopBeaconMonitoring(identifier: String) throws {
        core.removeRegion(identifier)
    }

    // MARK: - Heading and altitude

    func startHeadingUpdates(options: HeadingOptions) throws {
        core.startHeading(options)
    }

    func stopHeadingUpdates() throws {
        core.stopHeading()
    }

    func getCurrentHeading(timeoutMs: Double) throws -> Promise<Heading> {
        Self.promise { core.currentHeading(timeoutMs: timeoutMs, $0) }
    }

    func dismissHeadingCalibrationDisplay() throws {
        core.dismissHeadingCalibration()
    }

    func startAltitudeUpdates(absolute: Bool) throws {
        core.startAltitude(absolute: absolute)
    }

    func stopAltitudeUpdates() throws {
        core.stopAltitude()
    }

    // MARK: - GNSS (Android only)

    func getGnssInfo() throws -> Promise<GnssInfo> {
        Promise.resolved(withResult: GnssInfo(
            supported: false,
            hardwareModelName: nil,
            yearOfHardware: nil,
            hasMeasurements: false,
            hasNavigationMessages: false,
            hasAntennaInfo: false,
            hasSatelliteBlocklist: false,
            hasMeasurementCorrections: false,
            hasLowPowerMode: false,
            hasSatellitePvt: false,
            hasAccumulatedDeltaRange: false
        ))
    }

    func startGnssUpdates(options: GnssOptions) throws -> Promise<Void> {
        Self.unsupported("GNSS status, NMEA, and raw measurements (iOS has no public GNSS API)")
    }

    func stopGnssUpdates() throws {}

    // MARK: - Geocoding

    func isGeocoderAvailable() throws -> Bool {
        true
    }

    func geocode(address: String, options: GeocodeOptions) throws -> Promise<[Address]> {
        Self.promise { MunimLocationGeocoder.geocode(address: address, options: options, $0) }
    }

    func reverseGeocode(latitude: Double, longitude: Double, options: GeocodeOptions) throws -> Promise<[Address]> {
        Self.promise { MunimLocationGeocoder.reverseGeocode(latitude: latitude, longitude: longitude, options: options, $0) }
    }

    // MARK: - Mock locations (Android only)

    func setMockLocationEnabled(enabled: Bool) throws -> Promise<Bool> {
        Promise.resolved(withResult: false)
    }

    func setMockLocation(location: MockLocation) throws -> Promise<Void> {
        Self.unsupported("Mock locations (simulate locations with an Xcode GPX scheme instead)")
    }

    // MARK: - Utilities

    func isLocationAvailable() throws -> Promise<Bool> {
        Self.resolved { completion in
            core.permissionStatus { completion($0.locationServicesEnabled && $0.foreground) }
        }
    }

    func getCapabilities() throws -> Promise<LocationCapabilities> {
        Self.resolved { core.capabilities($0) }
    }
}
