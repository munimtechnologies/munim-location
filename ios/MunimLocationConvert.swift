//
//  MunimLocationConvert.swift
//  munim-location
//

import CoreLocation
import Foundation
import NitroModules

enum MunimLocationError {
    static func make(_ code: String, _ message: String) -> NSError {
        NSError(
            domain: "MunimLocation",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "\(code): \(message)"]
        )
    }

    static let permissionDenied = make("E_LOCATION_PERMISSION_DENIED", "Location permission has not been granted")
    static let servicesDisabled = make("E_LOCATION_SERVICES_DISABLED", "Location services are turned off")
    static let timeout = make("E_LOCATION_TIMEOUT", "Timed out waiting for a location")
    static let unavailable = make("E_LOCATION_UNAVAILABLE", "No location is available")
    static func unsupported(_ what: String) -> NSError {
        make("E_UNSUPPORTED", "\(what) is not supported on this device or iOS version")
    }
    static let backgroundModeMissing = make(
        "E_BACKGROUND_MODE_MISSING",
        "Add `location` to UIBackgroundModes in Info.plist (the Expo config plugin does this with `isIosBackgroundLocationEnabled`)"
    )
}

enum MunimLocationConvert {
    static func nowMs() -> Double { Date().timeIntervalSince1970 * 1000 }

    static func millis(_ date: Date) -> Double { date.timeIntervalSince1970 * 1000 }

    // MARK: - Location

    static func location(_ location: CLLocation) -> Location {
        var ellipsoidal: Double?
        var simulated: Bool?
        var accessory: Bool?
        if #available(iOS 15.0, *) {
            ellipsoidal = location.ellipsoidalAltitude
            if let info = location.sourceInformation {
                simulated = info.isSimulatedBySoftware
                accessory = info.isProducedByAccessory
            } else {
                simulated = false
                accessory = false
            }
        }
        let verticalValid = location.verticalAccuracy >= 0
        let speedValid = location.speed >= 0
        let courseValid = location.course >= 0
        var courseAccuracy: Double?
        if #available(iOS 13.4, *), location.courseAccuracy >= 0 {
            courseAccuracy = location.courseAccuracy
        }
        return Location(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            altitude: verticalValid ? location.altitude : nil,
            ellipsoidalAltitude: verticalValid ? ellipsoidal : nil,
            mslAltitude: nil,
            mslAltitudeAccuracy: nil,
            horizontalAccuracy: location.horizontalAccuracy,
            verticalAccuracy: verticalValid ? location.verticalAccuracy : nil,
            speed: speedValid ? location.speed : nil,
            speedAccuracy: location.speedAccuracy >= 0 ? location.speedAccuracy : nil,
            course: courseValid ? location.course : nil,
            courseAccuracy: courseAccuracy,
            timestamp: millis(location.timestamp),
            elapsedRealtimeNanos: nil,
            elapsedRealtimeUncertaintyNanos: nil,
            floor: location.floor.map { Double($0.level) },
            provider: "corelocation",
            isMock: simulated ?? false,
            isSimulatedBySoftware: simulated,
            isProducedByAccessory: accessory,
            satelliteCount: nil
        )
    }

    static func dict(_ location: Location) -> [String: Any] {
        var result: [String: Any] = [
            "latitude": location.latitude,
            "longitude": location.longitude,
            "horizontalAccuracy": location.horizontalAccuracy,
            "timestamp": location.timestamp,
            "isMock": location.isMock,
        ]
        result["altitude"] = location.altitude
        result["ellipsoidalAltitude"] = location.ellipsoidalAltitude
        result["verticalAccuracy"] = location.verticalAccuracy
        result["speed"] = location.speed
        result["speedAccuracy"] = location.speedAccuracy
        result["course"] = location.course
        result["courseAccuracy"] = location.courseAccuracy
        result["floor"] = location.floor
        result["provider"] = location.provider
        result["isSimulatedBySoftware"] = location.isSimulatedBySoftware
        result["isProducedByAccessory"] = location.isProducedByAccessory
        return result
    }

    static func dict(_ location: CLLocation) -> [String: Any] {
        dict(self.location(location))
    }

    // MARK: - Enums

    static func desiredAccuracy(_ accuracy: LocationAccuracy) -> CLLocationAccuracy {
        switch accuracy {
        case .bestfornavigation: return kCLLocationAccuracyBestForNavigation
        case .best: return kCLLocationAccuracyBest
        case .nearesttenmeters: return kCLLocationAccuracyNearestTenMeters
        case .hundredmeters: return kCLLocationAccuracyHundredMeters
        case .kilometer: return kCLLocationAccuracyKilometer
        case .threekilometers: return kCLLocationAccuracyThreeKilometers
        case .reduced:
            if #available(iOS 14.0, *) { return kCLLocationAccuracyReduced }
            return kCLLocationAccuracyThreeKilometers
        }
    }

    /// Horizontal accuracy (metres) good enough to resolve a one-shot request early.
    static func acceptableAccuracy(_ accuracy: LocationAccuracy) -> Double {
        switch accuracy {
        case .bestfornavigation, .best: return 15
        case .nearesttenmeters: return 25
        case .hundredmeters: return 100
        case .kilometer: return 1000
        case .threekilometers: return 3000
        case .reduced: return 5000
        }
    }

    static func activityType(_ type: ActivityType) -> CLActivityType {
        switch type {
        case .other: return .other
        case .automotivenavigation: return .automotiveNavigation
        case .fitness: return .fitness
        case .othernavigation: return .otherNavigation
        case .airborne:
            if #available(iOS 12.0, *) { return .airborne }
            return .other
        case .maritime:
#if compiler(>=6.4)
            if #available(iOS 27.0, *) { return .maritime }
#endif
            return .otherNavigation
        }
    }

    @available(iOS 17.0, *)
    static func liveConfiguration(_ type: ActivityType) -> CLLocationUpdate.LiveConfiguration {
        switch type {
        case .other: return .default
        case .automotivenavigation: return .automotiveNavigation
        case .othernavigation: return .otherNavigation
        case .fitness: return .fitness
        case .airborne: return .airborne
        case .maritime:
#if compiler(>=6.4)
            if #available(iOS 27.0, *) { return .maritime }
#endif
            return .otherNavigation
        }
    }

    static func headingOrientation(_ orientation: HeadingOrientation) -> CLDeviceOrientation {
        switch orientation {
        case .portrait: return .portrait
        case .portraitupsidedown: return .portraitUpsideDown
        case .landscapeleft: return .landscapeLeft
        case .landscaperight: return .landscapeRight
        case .faceup: return .faceUp
        case .facedown: return .faceDown
        }
    }

    static func authorizationStatus(_ status: CLAuthorizationStatus) -> AuthorizationStatus {
        switch status {
        case .notDetermined: return .notdetermined
        case .restricted: return .restricted
        case .denied: return .denied
        case .authorizedWhenInUse: return .wheninuse
        case .authorizedAlways: return .always
        @unknown default: return .notdetermined
        }
    }

    static func heading(_ heading: CLHeading) -> Heading {
        Heading(
            magneticHeading: heading.magneticHeading,
            trueHeading: heading.trueHeading,
            headingAccuracy: heading.headingAccuracy,
            x: heading.x,
            y: heading.y,
            z: heading.z,
            timestamp: millis(heading.timestamp),
            source: "corelocation"
        )
    }

    static func dict(_ heading: Heading) -> [String: Any] {
        var result: [String: Any] = [
            "magneticHeading": heading.magneticHeading,
            "trueHeading": heading.trueHeading,
            "headingAccuracy": heading.headingAccuracy,
            "timestamp": heading.timestamp,
            "source": heading.source,
        ]
        result["x"] = heading.x
        result["y"] = heading.y
        result["z"] = heading.z
        return result
    }

    static func dict(_ region: GeofenceRegion) -> [String: Any] {
        [
            "identifier": region.identifier,
            "latitude": region.latitude,
            "longitude": region.longitude,
            "radius": region.radius,
            "notifyOnEntry": region.notifyOnEntry,
            "notifyOnExit": region.notifyOnExit,
            "notifyOnDwell": region.notifyOnDwell,
            "loiteringDelayMs": region.loiteringDelayMs,
            "expirationMs": region.expirationMs,
            "notifyOnStartIfInside": region.notifyOnStartIfInside,
        ]
    }

    static func geofence(_ dict: [String: Any]) -> GeofenceRegion? {
        guard let identifier = dict["identifier"] as? String,
              let latitude = dict["latitude"] as? Double,
              let longitude = dict["longitude"] as? Double,
              let radius = dict["radius"] as? Double else {
            return nil
        }
        return GeofenceRegion(
            identifier: identifier,
            latitude: latitude,
            longitude: longitude,
            radius: radius,
            notifyOnEntry: dict["notifyOnEntry"] as? Bool ?? true,
            notifyOnExit: dict["notifyOnExit"] as? Bool ?? true,
            notifyOnDwell: dict["notifyOnDwell"] as? Bool ?? false,
            loiteringDelayMs: dict["loiteringDelayMs"] as? Double ?? 0,
            expirationMs: dict["expirationMs"] as? Double ?? 0,
            notifyOnStartIfInside: dict["notifyOnStartIfInside"] as? Bool ?? false
        )
    }

    static func proximity(_ proximity: CLProximity) -> String {
        switch proximity {
        case .immediate: return "immediate"
        case .near: return "near"
        case .far: return "far"
        case .unknown: return "unknown"
        @unknown default: return "unknown"
        }
    }

    static func beacon(_ beacon: CLBeacon) -> [String: Any] {
        var result: [String: Any] = [
            "major": beacon.major.intValue,
            "minor": beacon.minor.intValue,
            "proximity": proximity(beacon.proximity),
            "accuracy": beacon.accuracy,
            "rssi": beacon.rssi,
        ]
        if #available(iOS 13.0, *) {
            result["uuid"] = beacon.uuid.uuidString
            result["timestamp"] = millis(beacon.timestamp)
        } else {
            result["uuid"] = beacon.proximityUUID.uuidString
            result["timestamp"] = nowMs()
        }
        return result
    }

    static func visit(_ visit: CLVisit) -> [String: Any] {
        var result: [String: Any] = [
            "latitude": visit.coordinate.latitude,
            "longitude": visit.coordinate.longitude,
            "horizontalAccuracy": visit.horizontalAccuracy,
        ]
        if visit.arrivalDate != Date.distantPast {
            result["arrivalTimestamp"] = millis(visit.arrivalDate)
        }
        if visit.departureDate != Date.distantFuture {
            result["departureTimestamp"] = millis(visit.departureDate)
        }
        return result
    }

    static func errorDict(_ error: Error) -> [String: Any] {
        let nsError = error as NSError
        var code = "E_LOCATION_ERROR"
        if nsError.domain == kCLErrorDomain {
            switch CLError.Code(rawValue: nsError.code) {
            case .denied: code = "E_LOCATION_PERMISSION_DENIED"
            case .locationUnknown: code = "E_LOCATION_UNAVAILABLE"
            case .network: code = "E_NETWORK"
            case .headingFailure: code = "E_HEADING_FAILURE"
            case .regionMonitoringDenied, .regionMonitoringFailure, .regionMonitoringSetupDelayed:
                code = "E_REGION_MONITORING"
            case .rangingUnavailable, .rangingFailure: code = "E_RANGING"
            case .promptDeclined: code = "E_PROMPT_DECLINED"
            default: break
            }
        }
        return [
            "code": code,
            "message": nsError.localizedDescription,
            "nativeCode": nsError.code,
            "domain": nsError.domain,
        ]
    }
}
