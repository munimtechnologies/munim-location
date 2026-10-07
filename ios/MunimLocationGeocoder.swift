//
//  MunimLocationGeocoder.swift
//  munim-location
//
//  Forward and reverse geocoding. iOS 26 deprecates CLGeocoder in favour of
//  MapKit's MKGeocodingRequest / MKReverseGeocodingRequest; `auto` uses MapKit
//  there and CLGeocoder on older systems.
//

import Contacts
import CoreLocation
import Foundation
import MapKit
import NitroModules

enum MunimLocationGeocoder {
    static func geocode(address: String, options: GeocodeOptions, _ completion: @escaping (Result<[Address], Error>) -> Void) {
        DispatchQueue.main.async {
#if compiler(>=6.2)
            if options.provider != .clgeocoder, #available(iOS 26.0, *) {
                guard let request = MKGeocodingRequest(addressString: address) else {
                    completion(.failure(MunimLocationError.make("E_GEOCODE", "Invalid address string")))
                    return
                }
                request.preferredLocale = locale(options.locale)
                request.getMapItems { items, error in
                    complete(items: items, error: error, options: options, completion)
                }
                return
            }
#endif
            if options.provider == .mapkit {
                completion(.failure(MunimLocationError.unsupported("MapKit geocoding (iOS 26+)")))
                return
            }
            CLGeocoder().geocodeAddressString(address, in: nil, preferredLocale: locale(options.locale)) { placemarks, error in
                complete(placemarks: placemarks, error: error, options: options, completion)
            }
        }
    }

    static func reverseGeocode(latitude: Double, longitude: Double, options: GeocodeOptions, _ completion: @escaping (Result<[Address], Error>) -> Void) {
        let location = CLLocation(latitude: latitude, longitude: longitude)
        DispatchQueue.main.async {
#if compiler(>=6.2)
            if options.provider != .clgeocoder, #available(iOS 26.0, *) {
                guard let request = MKReverseGeocodingRequest(location: location) else {
                    completion(.failure(MunimLocationError.make("E_GEOCODE", "Invalid coordinate")))
                    return
                }
                request.preferredLocale = locale(options.locale)
                request.getMapItems { items, error in
                    complete(items: items, error: error, options: options, completion)
                }
                return
            }
#endif
            if options.provider == .mapkit {
                completion(.failure(MunimLocationError.unsupported("MapKit geocoding (iOS 26+)")))
                return
            }
            CLGeocoder().reverseGeocodeLocation(location, preferredLocale: locale(options.locale)) { placemarks, error in
                complete(placemarks: placemarks, error: error, options: options, completion)
            }
        }
    }

    private static func locale(_ identifier: String) -> Locale? {
        identifier.isEmpty ? nil : Locale(identifier: identifier.replacingOccurrences(of: "-", with: "_"))
    }

    private static func geocodeError(_ error: Error) -> Error {
        let nsError = error as NSError
        if nsError.domain == kCLErrorDomain, nsError.code == CLError.geocodeFoundNoResult.rawValue {
            return MunimLocationError.make("E_GEOCODE_NO_RESULT", "No result found")
        }
        return MunimLocationError.make("E_GEOCODE", nsError.localizedDescription)
    }

    private static func limit<T>(_ items: [T], _ options: GeocodeOptions) -> [T] {
        options.maxResults > 0 ? Array(items.prefix(Int(options.maxResults))) : items
    }

    private static func complete(placemarks: [CLPlacemark]?, error: Error?, options: GeocodeOptions, _ completion: @escaping (Result<[Address], Error>) -> Void) {
        if let error {
            let nsError = error as NSError
            if nsError.domain == kCLErrorDomain, nsError.code == CLError.geocodeFoundNoResult.rawValue {
                completion(.success([]))
            } else {
                completion(.failure(geocodeError(error)))
            }
            return
        }
        completion(.success(limit(placemarks ?? [], options).map { address($0, mapItem: nil) }))
    }

    private static func complete(items: [MKMapItem]?, error: Error?, options: GeocodeOptions, _ completion: @escaping (Result<[Address], Error>) -> Void) {
        if let error {
            let nsError = error as NSError
            if nsError.domain == MKError.errorDomain, nsError.code == Int(MKError.placemarkNotFound.rawValue) {
                completion(.success([]))
            } else {
                completion(.failure(geocodeError(error)))
            }
            return
        }
        completion(.success(limit(items ?? [], options).map { mapItemAddress($0) }))
    }

    private static func address(_ placemark: CLPlacemark, mapItem: MKMapItem?) -> Address {
        var formatted: String?
        if let postal = placemark.postalAddress {
            formatted = CNPostalAddressFormatterBridge.string(postal)
        }
        return Address(
            latitude: placemark.location?.coordinate.latitude,
            longitude: placemark.location?.coordinate.longitude,
            name: placemark.name,
            streetNumber: placemark.subThoroughfare,
            street: placemark.thoroughfare,
            subLocality: placemark.subLocality,
            locality: placemark.locality,
            subAdministrativeArea: placemark.subAdministrativeArea,
            administrativeArea: placemark.administrativeArea,
            postalCode: placemark.postalCode,
            country: placemark.country,
            isoCountryCode: placemark.isoCountryCode,
            timeZone: placemark.timeZone?.identifier,
            formattedAddress: formatted,
            shortAddress: nil,
            areasOfInterest: placemark.areasOfInterest,
            inlandWater: placemark.inlandWater,
            ocean: placemark.ocean,
            phone: mapItem?.phoneNumber,
            url: mapItem?.url?.absoluteString
        )
    }

    private static func mapItemAddress(_ item: MKMapItem) -> Address {
#if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            // Structured street-level fields still come from the (deprecated
            // in iOS 26) placemark; MapKit offers no replacement for them yet.
            let placemark = item.placemark
            let representations = item.addressRepresentations
            return Address(
                latitude: item.location.coordinate.latitude,
                longitude: item.location.coordinate.longitude,
                name: item.name,
                streetNumber: placemark.subThoroughfare,
                street: placemark.thoroughfare,
                subLocality: placemark.subLocality,
                locality: representations?.cityName ?? placemark.locality,
                subAdministrativeArea: placemark.subAdministrativeArea,
                administrativeArea: placemark.administrativeArea,
                postalCode: placemark.postalCode,
                country: representations?.regionName ?? placemark.country,
                isoCountryCode: placemark.isoCountryCode,
                timeZone: item.timeZone?.identifier,
                formattedAddress: item.address?.fullAddress
                    ?? representations?.fullAddress(includingRegion: true, singleLine: true),
                shortAddress: item.address?.shortAddress,
                areasOfInterest: placemark.areasOfInterest,
                inlandWater: placemark.inlandWater,
                ocean: placemark.ocean,
                phone: item.phoneNumber,
                url: item.url?.absoluteString
            )
        }
#endif
        return address(item.placemark, mapItem: item)
    }
}


private enum CNPostalAddressFormatterBridge {
    static func string(_ address: CNPostalAddress) -> String {
        CNPostalAddressFormatter.string(from: address, style: .mailingAddress)
            .replacingOccurrences(of: "\n", with: ", ")
    }
}
