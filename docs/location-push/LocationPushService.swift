//
//  LocationPushService.swift
//  Sample Location Push Service Extension for munim-location.
//
//  iOS wakes this extension for an APNs `location` push sent to the token
//  from `startMonitoringLocationPushes()`, even when the app is not running.
//  It takes one fix and POSTs it to the URL in the push, using the same field
//  names as munim-location's `Location` type, so a server can accept the
//  extension's report and a report sent from JavaScript (for example an
//  Android app answering an FCM data message with `getCurrentPosition()`)
//  with the same code.
//
//  Push payload (apns-push-type: location, apns-topic: <bundle id>.location-query):
//
//    {
//      "aps": {},
//      "munimLocation": {
//        "reportUrl": "https://api.example.com/location-reports",
//        "requestId": "req_123",
//        "token": "single-use secret the server checks",
//        "accuracy": "nearestTenMeters"   // optional munim-location LocationAccuracy
//      }
//    }
//
//  Report body (POST reportUrl, application/json):
//
//    { "requestId": "req_123", "token": "...", "location": <munim-location Location> }
//
//  The extension never sees the app's session; the single-use request id and
//  token are all the server needs to attribute the fix. Pushes are delivered
//  only while the person has granted Always location access.
//

import CoreLocation
import Foundation

private struct LocationRequest {
    let requestId: String
    let token: String
    let reportURL: URL
    let desiredAccuracy: CLLocationAccuracy

    init?(payload: [String: Any]) {
        guard
            let body = payload["munimLocation"] as? [String: Any],
            let requestId = body["requestId"] as? String, !requestId.isEmpty,
            let token = body["token"] as? String, !token.isEmpty,
            let urlString = body["reportUrl"] as? String,
            let reportURL = URL(string: urlString),
            reportURL.scheme == "https"
        else {
            return nil
        }
        self.requestId = requestId
        self.token = token
        self.reportURL = reportURL
        self.desiredAccuracy = LocationRequest.accuracy(body["accuracy"] as? String)
    }

    /// munim-location `LocationAccuracy` names to Core Location constants.
    private static func accuracy(_ name: String?) -> CLLocationAccuracy {
        switch name {
        case "bestForNavigation": return kCLLocationAccuracyBestForNavigation
        case "best": return kCLLocationAccuracyBest
        case "hundredMeters": return kCLLocationAccuracyHundredMeters
        case "kilometer": return kCLLocationAccuracyKilometer
        case "threeKilometers": return kCLLocationAccuracyThreeKilometers
        case "reduced": return kCLLocationAccuracyReduced
        default: return kCLLocationAccuracyNearestTenMeters
        }
    }
}

/// A munim-location `Location` object (see src/specs/munim-location.nitro.ts).
private func munimLocation(_ location: CLLocation) -> [String: Any] {
    var body: [String: Any] = [
        "latitude": location.coordinate.latitude,
        "longitude": location.coordinate.longitude,
        "horizontalAccuracy": location.horizontalAccuracy,
        "timestamp": location.timestamp.timeIntervalSince1970 * 1000,
        "provider": "corelocation",
    ]
    if location.verticalAccuracy >= 0 {
        body["altitude"] = location.altitude
        body["ellipsoidalAltitude"] = location.ellipsoidalAltitude
        body["verticalAccuracy"] = location.verticalAccuracy
    }
    if location.speed >= 0 {
        body["speed"] = location.speed
        if location.speedAccuracy >= 0 { body["speedAccuracy"] = location.speedAccuracy }
    }
    if location.course >= 0 {
        body["course"] = location.course
        if location.courseAccuracy >= 0 { body["courseAccuracy"] = location.courseAccuracy }
    }
    if let floor = location.floor {
        body["floor"] = floor.level
    }
    let simulated = location.sourceInformation?.isSimulatedBySoftware ?? false
    body["isMock"] = simulated
    if let source = location.sourceInformation {
        body["isSimulatedBySoftware"] = source.isSimulatedBySoftware
        body["isProducedByAccessory"] = source.isProducedByAccessory
    }
    return body
}

final class LocationPushService: NSObject, CLLocationPushServiceExtension, CLLocationManagerDelegate {
    private var completion: (() -> Void)?
    private var locationManager: CLLocationManager?
    private var request: LocationRequest?
    // iOS 18+: a CLServiceSession held while the extension runs.
    private var serviceSession: AnyObject?

    func didReceiveLocationPushPayload(_ payload: [String: Any], completion: @escaping () -> Void) {
        guard let request = LocationRequest(payload: payload) else {
            completion()
            return
        }
        self.request = request
        self.completion = completion

        if #available(iOS 18.0, *) {
            serviceSession = CLServiceSession(authorization: .always)
        }

        let manager = CLLocationManager()
        manager.delegate = self
        manager.desiredAccuracy = request.desiredAccuracy
        locationManager = manager
        manager.requestLocation()
    }

    /// iOS is about to end the extension; finish whatever is in flight.
    func serviceExtensionWillTerminate() {
        finish()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // requestLocation can call back more than once; report the first fix.
        guard let location = locations.last, let request else { return }
        self.request = nil
        report(location, for: request)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish()
    }

    private func report(_ location: CLLocation, for request: LocationRequest) {
        let body: [String: Any] = [
            "requestId": request.requestId,
            "token": request.token,
            "location": munimLocation(location),
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body) else {
            finish()
            return
        }
        var urlRequest = URLRequest(url: request.reportURL, timeoutInterval: 10)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = data
        URLSession.shared.dataTask(with: urlRequest) { [weak self] _, _, _ in
            DispatchQueue.main.async { self?.finish() }
        }.resume()
    }

    private func finish() {
        locationManager?.delegate = nil
        locationManager = nil
        serviceSession = nil
        let completion = self.completion
        self.completion = nil
        completion?()
    }
}
