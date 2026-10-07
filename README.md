<!-- Banner Image -->

<p align="center">
  <a href="https://github.com/munimtechnologies/munim-location">
    <img alt="Munim Technologies Location" height="128" src="./.github/resources/banner.png?v=1">
    <h1 align="center">munim-location</h1>
  </a>
</p>

<p align="center">
   <a aria-label="Package version" href="https://www.npmjs.com/package/munim-location" target="_blank">
    <img alt="Package version" src="https://img.shields.io/npm/v/munim-location.svg?style=flat-square&label=Version&labelColor=000000&color=0066CC" />
  </a>
  <a aria-label="Package is free to use" href="https://github.com/munimtechnologies/munim-location/blob/main/LICENSE" target="_blank">
    <img alt="License: Apache-2.0" src="https://img.shields.io/badge/License-Apache%202.0-success.svg?style=flat-square&color=33CC12" target="_blank" />
  </a>
  <a aria-label="package downloads" href="https://www.npmtrends.com/munim-location" target="_blank">
    <img alt="Downloads" src="https://img.shields.io/npm/dm/munim-location.svg?style=flat-square&labelColor=gray&color=33CC12&label=Downloads" />
  </a>
  <a aria-label="total package downloads" href="https://www.npmjs.com/package/munim-location" target="_blank">
    <img alt="Total Downloads" src="https://img.shields.io/npm/dt/munim-location.svg?style=flat-square&labelColor=gray&color=0066CC&label=Total%20Downloads" />
  </a>
</p>

<p align="center">
  <a aria-label="try with expo" href="https://docs.expo.dev/"><b>Works with Expo</b></a>
&ensp;•&ensp;
  <a aria-label="documentation" href="https://github.com/munimtechnologies/munim-location#readme">Read the Documentation</a>
&ensp;•&ensp;
  <a aria-label="report issues" href="https://github.com/munimtechnologies/munim-location/issues">Report Issues</a>
</p>

<h6 align="center">Follow Munim Technologies</h6>
<p align="center">
  <a aria-label="Follow Munim Technologies on GitHub" href="https://github.com/munimtechnologies" target="_blank">
    <img alt="Munim Technologies on GitHub" src="https://img.shields.io/badge/GitHub-222222?style=for-the-badge&logo=github&logoColor=white" target="_blank" />
  </a>&nbsp;
  <a aria-label="Follow Munim Technologies on LinkedIn" href="https://linkedin.com/in/sheehanmunim" target="_blank">
    <img alt="Munim Technologies on LinkedIn" src="https://img.shields.io/badge/LinkedIn-0077B5?style=for-the-badge&logo=linkedin&logoColor=white" target="_blank" />
  </a>&nbsp;
  <a aria-label="Visit Munim Technologies Website" href="https://munimtech.com" target="_blank">
    <img alt="Munim Technologies Website" src="https://img.shields.io/badge/Website-0066CC?style=for-the-badge&logo=globe&logoColor=white" target="_blank" />
  </a>
</p>

## Introduction

**munim-location** is a comprehensive React Native location library. It covers permissions (foreground, background, precise and approximate), one-shot and continuous positions, background and terminated-app delivery, geofencing, iBeacons, the compass, the barometer, Android GNSS (satellites, NMEA, raw measurements), forward and reverse geocoding, mock locations, and capability reporting, on iOS (Core Location, Core Motion, MapKit) and Android (Fused Location Provider, LocationManager, Geofencing, GNSS).

**Fully compatible with Expo!** Works with both Expo managed (development builds) and bare workflows, and ships an Expo config plugin for usage strings, background modes, and Android permissions.

**Built with React Native's Nitro modules architecture** for high performance and reliability.

**Note**: Location is heavily platform-gated. This library exposes what iOS and Android make available to third-party apps and reports the rest through `getCapabilities()`, explicit `E_UNSUPPORTED` errors, and the [Platform Support Matrix](#platform-support-matrix), instead of silently pretending it works.

## Table of contents

- [📚 Documentation](#-documentation)
- [🚀 Features](#-features)
- [Platform Support Matrix](#platform-support-matrix)
- [📦 Installation](#-installation)
- [Background and Terminated Behavior](#background-and-terminated-behavior)
- [⚡ Quick Start](#-quick-start)
- [🔧 API Reference](#-api-reference)
- [🔍 Troubleshooting](#-troubleshooting)
- [👏 Contributing](#-contributing)
- [📄 License](#-license)

## 📚 Documentation

<p>Learn about building location features <a aria-label="documentation" href="https://github.com/munimtechnologies/munim-location#readme">in our documentation!</a></p>

- [Getting Started](#-installation)
- [API Reference](#-api-reference)
- [Background and Terminated Behavior](#background-and-terminated-behavior)
- [Troubleshooting](#-troubleshooting)

## 🚀 Features

### Permissions and Services

- 🔐 **Foreground and Background Permission**: When-in-use and always flows, including the iOS provisional-always flow and the Android 10/11+ background flow (the system settings page on Android 11+)
- 🎯 **Precise vs Approximate**: iOS accuracy authorization and `requestTemporaryFullAccuracy(purposeKey)`, Android fine vs coarse
- 💬 **Rationale and Settings**: `shouldShowRationale()`, `openAppSettings()`, `openLocationSettings()`
- 🛰️ **Services and Providers**: system switch, GPS / network / passive / fused provider status, airplane mode
- 🧰 **Android Settings Resolution**: `checkLocationSettings()` and the Google Play services "turn on location" dialog
- 🍏 **iOS 17/18 Sessions**: `CLServiceSession` (when-in-use, always, full accuracy) and `CLBackgroundActivitySession`, with diagnostics events

### Positions

- 📍 **Current Position**: accuracy, timeout, maximum age, Android priorities (high accuracy, balanced, low power, passive) and `CurrentLocationRequest` options
- 🕘 **Last Known Position**: cached fix with age and accuracy filters, never turns on GPS
- 🔁 **Continuous Updates**: distance filter, interval, fastest interval, batching (`maxUpdateDelayMs`), max update age, update count, granularity, iOS activity type and auto-pause
- ✨ **iOS 17+ Live Updates**: `CLLocationUpdate.liveUpdates` with stationary and accuracy diagnostics
- 📦 **Full Location Object**: altitude (ellipsoidal on iOS 15+, MSL on Android 14+), vertical / speed / course accuracy, floor, provider, elapsed realtime, satellite count, mock and simulated flags

### Background and Terminated Delivery

- 🌙 **Background Updates**: iOS background location mode with the blue indicator, Android `location` foreground service with a configurable notification, or Android PendingIntent delivery
- 🚶 **Significant Changes and Visits**: iOS significant-change monitoring and `CLVisit`; Android low-power PendingIntent equivalent
- 💤 **Headless Delivery**: `registerBackgroundHandler()` receives events when iOS relaunches the app or Android starts it without UI (Headless JS), with a persisted replay queue
- 🔄 **Resume After Reboot**: Android restores background tracking, significant changes, and geofences on `BOOT_COMPLETED`

### Regions

- 🗺️ **Geofencing**: circular regions with enter / exit / dwell, initial state, expiration, monitored-region list, state queries, and limits (iOS 17+ uses `CLMonitor`)
- 📡 **iBeacon (iOS)**: ranging and region monitoring

### Heading, Motion, and GNSS

- 🧭 **Compass**: magnetic and true heading, accuracy, heading filter, device orientation, iOS calibration overlay; Android Fused Orientation Provider (13+) or rotation-vector sensor
- ⛰️ **Altimeter**: iOS `CMAltimeter` relative and absolute altitude, Android pressure sensor
- 🛰️ **GNSS (Android)**: satellites (constellation, C/N0, elevation, azimuth, used in fix, almanac / ephemeris, carrier frequency), time to first fix, NMEA, hardware model / year, capabilities, and opt-in raw measurements and navigation messages

### Geocoding and Utilities

- 🏠 **Geocoding**: forward and reverse, structured addresses, locale; iOS 26 `MKGeocodingRequest` / `MKReverseGeocodingRequest` with `CLGeocoder` before; Android `Geocoder` with the API 33 async listener
- 📏 **Geodesy**: `getDistance()` (WGS84 Vincenty), `getBearing()`, `getDestination()`, `isPointWithinRadius()`
- 🧪 **Mock Locations (Android)**: test provider and fused mock mode for development builds
- 🧭 **Capability Reporting**: `getCapabilities()` reports what this device and OS can do
- 🎯 **TypeScript Support**: every function, option, event, and payload is typed
- 🚀 **Expo Compatible**: config plugin for usage strings, background modes, permissions, boot receiver, and notification defaults

## Platform Support Matrix

| Capability | iOS | Android | Notes |
| --- | --- | --- | --- |
| Foreground / background permission | ✅ | ✅ | iOS: when-in-use, always (provisional flow when nothing is granted yet; one-time upgrade prompt from when-in-use). Android: fine/coarse, then `ACCESS_BACKGROUND_LOCATION` (Android 10 dialog, Android 11+ settings page). |
| Precise vs approximate | ✅ | ✅ | iOS 14+ accuracy authorization and `requestTemporaryFullAccuracy()` (needs `NSLocationTemporaryUsageDescriptionDictionary`). Android fine vs coarse; `requestForegroundPermission({ precise: false })` asks for coarse only. |
| Rationale / settings shortcuts | ➖ | ✅ | `shouldShowRationale()` is Android-only (iOS returns `false`). iOS has no public deep link to Location Services; `openLocationSettings()` opens the app's Settings page. |
| Location settings resolution dialog | ❌ | ✅ | Google Play services `SettingsClient`. iOS shows its own "turn on Location Services" alert; `checkLocationSettings()` reports services + permission there. |
| Provider status / provider change events | ➖ | ✅ | Android reports gps / network / passive / fused and airplane mode, and emits `providerChanged` / `airplaneModeChanged`. iOS reports the services switch only (`providers: ['corelocation']`) and emits `providerChanged` with authorization changes. |
| `CLServiceSession` / `CLBackgroundActivitySession` | ✅ | ❌ | iOS 18 / iOS 17. Android returns `{ supported: false }`. |
| Current / last known position | ✅ | ✅ | Android uses the Fused Location Provider, or LocationManager when Google Play services is missing. |
| Continuous updates | ✅ | ✅ | Interval, fastest interval, batching, max update age, and granularity are Android-only; activity type and auto-pause are iOS-only. |
| iOS 17+ live updates | ✅ | ❌ | `watchPosition(..., { useLiveUpdates: true })`; diagnostics need iOS 18. |
| Location availability events | ❌ | ✅ | Android `LocationCallback.onLocationAvailability`. |
| Ellipsoidal / MSL altitude, floor | ✅ | ✅ | Ellipsoidal altitude and floor on iOS; MSL altitude on Android 14+. |
| Mock / simulated detection | ✅ | ✅ | iOS 15+ `isSimulatedBySoftware` / `isProducedByAccessory`; Android `isMock`. |
| Mock location provider | ❌ | ✅ | Android development builds, with the app selected as the mock location app. On iOS use an Xcode GPX scheme. |
| Background updates | ✅ | ✅ | iOS: `location` background mode. Android: `location` foreground service (Android 14+ needs `FOREGROUND_SERVICE_LOCATION`) or PendingIntent delivery (throttled to a few updates per hour in the background). |
| Significant-change monitoring | ✅ | ➖ | Android has no equivalent API; the package uses a low-power ~500 m / 5 min PendingIntent request. |
| Visits (`CLVisit`) | ✅ | ❌ | No Android equivalent; rejects with `E_UNSUPPORTED`. |
| Deferred / batched delivery | ➖ | ✅ | Android `maxUpdateDelayMs`. Apple removed deferred updates in iOS 13; iOS delivers each fix. |
| Headless / terminated delivery | ✅ | ✅ | iOS relaunches for significant changes, visits, and regions (not for standard updates). Android Headless JS from receivers and the foreground service. |
| Resume after reboot | ➖ | ✅ | Android `BOOT_COMPLETED` (needs `RECEIVE_BOOT_COMPLETED`). iOS resumes significant changes, visits, and regions itself; background updates resume when the app next launches. |
| Geofencing | ✅ | ✅ | iOS: 20 regions per app (shared with beacon regions), `CLMonitor` on iOS 17+, region monitoring before. Android: 100 per app, fine + background permission on Android 10+. |
| Geofence dwell | ✅ | ✅ | Android native `loiteringDelay`; iOS timer after entry (only while the process runs). |
| iBeacon ranging / monitoring | ✅ | ❌ | Android has no iBeacon API; scan BLE advertisements instead (for example with munim-bluetooth). |
| Compass heading | ✅ | ✅ | Android 13+ Fused Orientation Provider when Google Play services is present, otherwise the rotation-vector sensor. |
| Altimeter | ✅ | ✅ | iOS `CMAltimeter` (absolute altitude on iOS 15+, needs `NSMotionUsageDescription`). Android pressure sensor (standard-atmosphere estimate). |
| GNSS status, NMEA, raw measurements | ❌ | ✅ | iOS has no public GNSS API. Raw measurements and navigation messages are opt-in. |
| Geocoding | ✅ | ✅ | iOS 26+ MapKit requests, `CLGeocoder` before (deprecated in iOS 26). Android `Geocoder` needs a backend (Google Play services devices). |

Call `getCapabilities()` at runtime when you need optional behavior. Support still varies by OS version, hardware, permissions, and app state.

## 📦 Installation

### React Native CLI

```bash
npm install munim-location react-native-nitro-modules
# or
yarn add munim-location react-native-nitro-modules
```

### Expo

```bash
npx expo install munim-location react-native-nitro-modules
```

> **Note**: This library requires Expo SDK 50+ and works with both managed and bare workflows. To support Nitro modules, you need React Native version v0.78.0 or higher.
>
> **Important**: This package requires a native development build in Expo. It does not work in Expo Go. After installing, run `npx expo run:ios`, `npx expo run:android`, or create a development build with EAS.

Add the config plugin to `app.json`:

```json
{
  "expo": {
    "plugins": [
      [
        "munim-location",
        {
          "locationWhenInUsePermission": "Show your position on the map.",
          "locationAlwaysAndWhenInUsePermission": "Track your runs while the app is closed.",
          "temporaryFullAccuracyPurposes": {
            "Navigation": "Turn-by-turn directions need your precise location."
          },
          "isIosBackgroundLocationEnabled": true,
          "isAndroidBackgroundLocationEnabled": true,
          "isAndroidForegroundServiceEnabled": true,
          "androidBootReceiver": true,
          "androidNotificationTitle": "Tracking your run",
          "androidNotificationIcon": "ic_notification"
        }
      ]
    ]
  }
}
```

| Option | Default | Effect |
| --- | --- | --- |
| `locationWhenInUsePermission` | generic text | `NSLocationWhenInUseUsageDescription`. `false` leaves it alone. |
| `locationAlwaysAndWhenInUsePermission` | generic text | `NSLocationAlwaysAndWhenInUseUsageDescription`. `false` leaves it alone. |
| `locationAlwaysPermission` | not set | Legacy `NSLocationAlwaysUsageDescription`. |
| `temporaryFullAccuracyPurposes` | not set | `NSLocationTemporaryUsageDescriptionDictionary` (keys are purpose keys). |
| `motionPermission` | generic text | `NSMotionUsageDescription` (altimeter). `false` skips it. |
| `isIosBackgroundLocationEnabled` | `false` | Adds `location` to `UIBackgroundModes`. |
| `androidPermissions` | `['fine', 'coarse']` | Any of `fine`, `coarse`, `background`; `false` manages them yourself. |
| `isAndroidBackgroundLocationEnabled` | `false` | Adds `ACCESS_BACKGROUND_LOCATION`. |
| `isAndroidForegroundServiceEnabled` | same as background | Adds `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`. |
| `androidBootReceiver` | `false` | Adds `RECEIVE_BOOT_COMPLETED` so tracking and geofences resume after reboot. |
| `androidNotificationTitle`, `androidNotificationText`, `androidNotificationChannelId`, `androidNotificationChannelName`, `androidNotificationIcon`, `androidNotificationColor` | built-in | Foreground-service notification defaults (manifest meta-data). Per-call `startBackgroundUpdates({ android })` values win. |

### iOS Setup

For iOS, the library is automatically linked. Add the usage strings you need to `Info.plist`:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Show your position on the map.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Track your runs while the app is closed.</string>
<key>NSLocationTemporaryUsageDescriptionDictionary</key>
<dict>
  <key>Navigation</key>
  <string>Turn-by-turn directions need your precise location.</string>
</dict>
<!-- Only for startAltitudeUpdates() -->
<key>NSMotionUsageDescription</key>
<string>Measure altitude changes with the barometer.</string>
<!-- Only for background updates -->
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
</array>
```

`startBackgroundUpdates()` and `watchPosition({ allowsBackgroundLocationUpdates: true })` check for the `location` background mode first and reject with `E_BACKGROUND_MODE_MISSING` instead of letting Core Location crash. Significant changes, visits, and regions do not need the background mode.

**For Expo projects**, use the config plugin above, or set the keys under `expo.ios.infoPlist`.

### Android Setup

The library does not merge location permissions into your app. Declare only what you use in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<!-- Background updates, geofencing on Android 10+ -->
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<!-- startBackgroundUpdates() in foregroundService mode -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- Resume tracking and geofences after reboot -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

The library manifest declares its own components: the `location`-type foreground service, the Headless JS service, the PendingIntent receiver, and the boot receiver (which only fires when your app holds `RECEIVE_BOOT_COMPLETED`). It also adds `WAKE_LOCK` for Headless JS.

Android 14+ refuses a `location` foreground service without `FOREGROUND_SERVICE_LOCATION` and a granted location permission; `startBackgroundUpdates()` checks the manifest first and rejects with `E_FOREGROUND_SERVICE`. Google Play asks apps that declare `ACCESS_BACKGROUND_LOCATION` or a `location` foreground service to justify it in the Play Console.

**Build defaults.** The Android library compiles against `compileSdk` 37 with `minSdk` 24, matching React Native 0.87 (AGP 9.2, Kotlin 2.2), and depends on `com.google.android.gms:play-services-location`. An app's `rootProject.ext` values (`compileSdkVersion`, `minSdkVersion`, `targetSdkVersion`, `ndkVersion`) override them. Without Google Play services the package falls back to `LocationManager` for positions; geofencing, the settings dialog, and the fused orientation provider need Google Play services.

## Background and Terminated Behavior

Background-origin events are `backgroundLocation`, `significantLocationChange`, `visit`, `geofenceTransition`, `geofenceExpired`, `geofenceError`, `backgroundLaunch`, `locationUpdatesPaused`, and `locationUpdatesResumed`. Register one handler for them at the top level of your entry file, not inside a component:

```typescript
// index.js
import { AppRegistry } from 'react-native'
import { registerBackgroundHandler } from 'munim-location'
import App from './App'

registerBackgroundHandler(async (event) => {
  if (event.name === 'backgroundLocation') {
    await saveTrackPoints(event.payload.locations)
  } else if (event.name === 'geofenceTransition') {
    await notifyArrival(event.payload.identifier, event.payload.transition)
  }
})

AppRegistry.registerComponent('MyApp', () => App)
```

| State | iOS | Android |
| --- | --- | --- |
| App in background or suspended | Background updates keep running with the `location` background mode (blue indicator when `showsBackgroundLocationIndicator`). Significant changes, visits, and regions wake the suspended app. | The `location` foreground service keeps running with its notification. PendingIntent mode keeps working but Android throttles background apps to a few updates per hour. |
| App terminated by the system | iOS relaunches the app in the background for significant changes, visits, and region events (not for standard updates). The package's launch observer recreates its managers before your app delegate finishes launching, so the event is not lost; it emits `backgroundLaunch` and persists the events until JavaScript attaches. | Receivers and the foreground service (`START_STICKY`) deliver events through the Headless JS task `MunimLocationHeadlessTask`. If Android refuses to start it, the event is persisted and replayed by `registerBackgroundHandler()`. |
| Device reboot | iOS resumes significant changes, visits, and regions. Continuous background updates resume when the app next launches. | With `RECEIVE_BOOT_COMPLETED`, geofences and significant changes are re-registered, and background tracking restarts when it was started with `android.restartOnBoot: true` (falling back to PendingIntent delivery if the foreground service is refused). |
| User force-quits / force-stops | iOS stops relaunching the app for location events except region monitoring and significant changes, which still relaunch it. | Nothing runs until the user opens the app again. |

**iOS and the UIScene lifecycle.** When iOS relaunches a terminated app in the background there is no scene, so apps that start React Native from `SceneDelegate` (required for Xcode 27 builds) do not run JavaScript during that launch. The package records the events natively; `registerBackgroundHandler()` replays them (`event.replayed === true`) the next time JavaScript starts. Apps that must run JavaScript during a background relaunch can start React Native from the app delegate when `launchOptions[.location]` is set.

```typescript
import {
  requestPermission,
  startBackgroundUpdates,
  stopBackgroundUpdates,
} from 'munim-location'

const permission = await requestPermission({ background: true })
if (!permission.background) {
  throw new Error('Background location was not granted')
}

await startBackgroundUpdates({
  accuracy: 'best',
  distanceFilter: 25,
  activityType: 'fitness',
  showsBackgroundLocationIndicator: true,
  android: {
    mode: 'foregroundService',
    notificationTitle: 'Recording your run',
    notificationText: 'Tap to open',
    restartOnBoot: true,
  },
})

// Later:
await stopBackgroundUpdates()
```

## ⚡ Quick Start

### Current Position

```typescript
import {
  getCurrentPosition,
  requestForegroundPermission,
} from 'munim-location'

const permission = await requestForegroundPermission()
if (!permission.foreground) {
  throw new Error('Location permission was not granted')
}

const location = await getCurrentPosition({
  accuracy: 'best',
  timeoutMs: 15000,
  maximumAgeMs: 60000,
})
console.log(location.latitude, location.longitude, location.horizontalAccuracy)
```

### Continuous Updates

```typescript
import { clearWatch, watchPosition } from 'munim-location'

const watchId = watchPosition(
  (location) => console.log('moved', location),
  (error) => console.warn(error.code, error.message),
  { accuracy: 'nearestTenMeters', distanceFilter: 10, intervalMs: 5000 }
)

// Later:
clearWatch(watchId)
```

### Geofences

```typescript
import { addEventListener, addGeofence } from 'munim-location'

await addGeofence({
  identifier: 'office',
  latitude: 37.3349,
  longitude: -122.009,
  radius: 150,
  notifyOnDwell: true,
  loiteringDelayMs: 5 * 60 * 1000,
})

const unsubscribe = addEventListener('geofenceTransition', (event) => {
  console.log(event.identifier, event.transition)
})
```

### Compass and Geocoding

```typescript
import {
  addEventListener,
  reverseGeocode,
  startHeadingUpdates,
} from 'munim-location'

startHeadingUpdates({ headingFilter: 2 })
addEventListener('heading', (heading) => console.log(heading.trueHeading))

const [address] = await reverseGeocode({ latitude: 48.8584, longitude: 2.2945 })
console.log(address?.formattedAddress)
```

## 🔧 API Reference

Every Promise rejects with a `LocationError` whose `code` is stable (`E_LOCATION_PERMISSION_DENIED`, `E_LOCATION_SERVICES_DISABLED`, `E_LOCATION_TIMEOUT`, `E_LOCATION_UNAVAILABLE`, `E_BACKGROUND_MODE_MISSING`, `E_FOREGROUND_SERVICE`, `E_GEOFENCE`, `E_GEOFENCE_LIMIT`, `E_GEOCODE`, `E_MOCK_LOCATION`, `E_NO_ACTIVITY`, `E_UNSUPPORTED`, ...).

### Permissions

#### `getPermissionStatus()`

Returns the current permission state without prompting.

**Returns:** `Promise<PermissionStatus>` — `{ status, accuracy, foreground, background, precise, canAskAgain, locationServicesEnabled }`. `status` is `notDetermined`, `restricted`, `denied`, `whenInUse`, or `always`; `accuracy` is `full` or `reduced`.

#### `requestForegroundPermission(options?)`

Requests when-in-use access. `options.precise` (default `true`) controls whether Android asks for `ACCESS_FINE_LOCATION`; with `false` it asks for `ACCESS_COARSE_LOCATION` only. iOS resolves once the user answers.

**Returns:** `Promise<PermissionStatus>`

#### `requestBackgroundPermission()`

Requests always / background access. iOS: when nothing is granted yet, the provisional-always flow (the user sees the when-in-use prompt and iOS offers the upgrade later); from when-in-use, the one-time upgrade prompt. iOS does not call back when it decides not to show a prompt, so the Promise resolves with the current status after the prompt closes or after a short grace period. Android 10 shows the dialog; Android 11+ opens the app's location settings page. Request foreground access first.

**Returns:** `Promise<PermissionStatus>`

#### `requestPermission(options?)`

Convenience: `requestForegroundPermission()` and then, when `options.background` is `true` and foreground access was granted, `requestBackgroundPermission()`.

#### `requestTemporaryFullAccuracy(purposeKey)`

iOS 14+: asks for full accuracy for this session when the user chose approximate location. `purposeKey` must be a key of `NSLocationTemporaryUsageDescriptionDictionary`. Android resolves with the current accuracy.

**Returns:** `Promise<'full' | 'reduced'>`

#### `shouldShowRationale(permission?)`

Android `shouldShowRequestPermissionRationale` for `fine` (default), `coarse`, or `background`. Always `false` on iOS.

#### `openAppSettings()`, `openLocationSettings()`

Open the app's settings page, or Android's Location settings (iOS opens the app's settings page for both).

### Services and Settings

#### `isLocationServicesEnabled()`

**Returns:** `Promise<boolean>` — the system-wide location switch.

#### `getProviderStatus()`

**Returns:** `Promise<ProviderStatus>` — `{ locationServicesEnabled, gpsEnabled, networkEnabled, passiveEnabled, fusedEnabled, airplaneModeOn, providers }`.

#### `checkLocationSettings(options?)`

Android `SettingsClient.checkLocationSettings` for `options.priority` (default `highAccuracy`) and `options.needBle`. Resolves `{ satisfied, resolvable, locationUsable, gpsUsable, networkLocationUsable, bleUsable, statusCode, message, ... }`. iOS reports the services switch and permission.

#### `requestLocationSettingsResolution(options?)`

Android: shows the Google Play services dialog that turns location (or high accuracy) on and resolves whether the settings are satisfied afterwards; also emits `locationSettingsResolved`. iOS resolves whether location is usable.

#### `startServiceSession(options?)`

iOS 18+ `CLServiceSession`. `options.authorization` is `none`, `whenInUse` (default), or `always`; `options.fullAccuracyPurposeKey` requests full accuracy. Returns `{ id, supported, stop() }` and emits `serviceSessionDiagnostic`. Android returns `supported: false`.

#### `startBackgroundActivitySession()`

iOS 17+ `CLBackgroundActivitySession` (start it in the foreground). Returns `{ id, supported, stop() }`; iOS 18 emits `backgroundActivitySessionDiagnostic`. Android returns `supported: false`.

### Positions

#### `getCurrentPosition(options?)`

One-shot position.

**Parameters:**

- `accuracy?` (`bestForNavigation` | `best` | `nearestTenMeters` | `hundredMeters` | `kilometer` | `threeKilometers` | `reduced`, default `best`)
- `priority?` (`auto` | `highAccuracy` | `balanced` | `lowPower` | `passive`, default `auto` = derived from `accuracy`; Android)
- `timeoutMs?` (default `30000`): rejects with `E_LOCATION_TIMEOUT`. iOS resolves with the best fix seen when one arrived but never reached the requested accuracy.
- `maximumAgeMs?` (default `0`): accept a cached fix this fresh.
- `granularity?` (`permissionLevel` | `fine` | `coarse`; Android)
- `durationMs?`: Android `CurrentLocationRequest` duration (defaults to `timeoutMs`).

**Returns:** `Promise<Location>`

#### `getLastKnownPosition(options?)`

The most recent cached fix, filtered by `maximumAgeMs` and `requiredAccuracy` (metres). Never turns on GPS.

**Returns:** `Promise<Location | undefined>`

#### `watchPosition(onLocation, onError?, options?)`

Continuous updates; returns a watch id.

**Options** (all optional): `accuracy`, `priority`, `granularity`, `distanceFilter` (metres), `intervalMs` (default `5000`), `fastestIntervalMs`, `maxUpdateDelayMs` (Android batching), `minUpdateAgeMs`, `maxUpdates`, `waitForAccurateLocation` (Android), `activityType` (`other` | `automotiveNavigation` | `fitness` | `otherNavigation` | `airborne` | `maritime`; iOS, `maritime` needs iOS 27), `pausesLocationUpdatesAutomatically` (iOS), `useLiveUpdates` (iOS 17+ `CLLocationUpdate.liveUpdates`), `allowsBackgroundLocationUpdates` and `showsBackgroundLocationIndicator` (iOS).

#### `clearWatch(watchId)`, `clearAllWatches()`

Stop one or every watch.

#### `Location`

```typescript
interface Location {
  latitude: number
  longitude: number
  altitude?: number            // iOS: MSL; Android: WGS84 ellipsoid
  ellipsoidalAltitude?: number // iOS 15+
  mslAltitude?: number         // Android 14+
  mslAltitudeAccuracy?: number // Android 14+
  horizontalAccuracy: number
  verticalAccuracy?: number
  speed?: number               // m/s
  speedAccuracy?: number
  course?: number              // degrees from true north
  courseAccuracy?: number
  timestamp: number            // epoch ms
  elapsedRealtimeNanos?: number            // Android
  elapsedRealtimeUncertaintyNanos?: number // Android 10+
  floor?: number               // iOS
  provider?: string            // 'gps' | 'network' | 'fused' | 'corelocation' ...
  isMock: boolean
  isSimulatedBySoftware?: boolean // iOS 15+
  isProducedByAccessory?: boolean // iOS 15+
  satelliteCount?: number         // Android, when reported
}
```

### Background

#### `registerBackgroundHandler(handler)`

Registers the handler for background-origin events (see [Background and Terminated Behavior](#background-and-terminated-behavior)) and replays persisted events. The handler receives `{ name, payload, timestamp, replayed, headless }`. Returns an unregister function.

#### `getPendingBackgroundEvents()`, `clearPendingBackgroundEvents()`

Read or drop events persisted while no JavaScript listener was attached (at most 500 are kept).

#### `startBackgroundUpdates(options?)`

Continuous updates that keep running in the background and arrive as `backgroundLocation`. Takes the `watchPosition` options (with `allowsBackgroundLocationUpdates: true` and `intervalMs: 10000` by default) plus `android`:

- `mode` (`foregroundService` default | `pendingIntent`)
- `notificationTitle`, `notificationText`, `notificationChannelId`, `notificationChannelName`, `notificationIconResourceName`, `notificationColor` (`#RRGGBB`)
- `restartOnBoot` (default `false`), `stopOnTaskRemoved` (default `false`)

**Returns:** `Promise<boolean>`

#### `stopBackgroundUpdates()`

**Returns:** `Promise<void>`

#### `startSignificantLocationChanges()`, `stopSignificantLocationChanges()`

iOS significant-change monitoring (relaunches a terminated app); Android low-power PendingIntent request. Locations arrive as `significantLocationChange`.

#### `startVisitMonitoring()`, `stopVisitMonitoring()`

iOS `CLVisit` monitoring; visits arrive as `visit` with `arrivalTimestamp` / `departureTimestamp`. Android rejects with `E_UNSUPPORTED`.

#### `getBackgroundStatus()`

**Returns:** `BackgroundStatus` — `{ running, mode, significantChanges, visits, geofenceCount, pendingEventCount }`.

### Geofencing

#### `addGeofence(region)`, `addGeofences(regions)`

**Parameters:**

- `identifier` (string), `latitude`, `longitude`, `radius` (metres)
- `notifyOnEntry?` (default `true`), `notifyOnExit?` (default `true`), `notifyOnDwell?` (default `false`), `loiteringDelayMs?` (default `30000`)
- `expirationMs?` (default `0` = never)
- `notifyOnStartIfInside?` (default `false`): emit `enter` right away when already inside.

Re-adding an identifier replaces it. Transitions arrive as `geofenceTransition` (`enter`, `exit`, `dwell`), state determinations as `geofenceState`.

#### `removeGeofence(identifier)`, `removeAllGeofences()`

#### `getMonitoredGeofences()`

**Returns:** `Promise<GeofenceRegion[]>`

#### `requestGeofenceState(identifier)`

**Returns:** `Promise<'inside' | 'outside' | 'unknown'>`. Android answers from the last transition, or compares the last known fix with the circle.

#### `getMaxMonitoredGeofences()`

`20` on iOS (shared with beacon regions), `100` on Android.

### Beacons (iOS)

#### `startBeaconRanging({ identifier, uuid, major?, minor? })`, `stopBeaconRanging(identifier)`

Results arrive as `beaconsRanged` (`{ identifier, beacons: [{ uuid, major, minor, proximity, accuracy, rssi, timestamp }] }`). Android emits `beaconRangingError` with `E_UNSUPPORTED`.

#### `startBeaconMonitoring({ identifier, uuid, major?, minor?, notifyEntryStateOnDisplay? })`, `stopBeaconMonitoring(identifier)`

Beacon-region transitions arrive as `geofenceTransition` with `kind: 'beacon'`.

### Heading and Altitude

#### `startHeadingUpdates(options?)`, `stopHeadingUpdates()`

Compass updates as `heading` events (`{ magneticHeading, trueHeading, headingAccuracy, x?, y?, z?, timestamp, source }`). Options: `headingFilter` (degrees, default `1`), `orientation` (`portrait`, `portraitUpsideDown`, `landscapeLeft`, `landscapeRight`, `faceUp`, `faceDown`), `showsCalibrationDisplay` (iOS, default `true`), `useFusedOrientation` (Android 13+, default `true`), `samplingPeriodMs` (Android, default `100`). `trueHeading` is `-1` until a location fix is known.

#### `getCurrentHeading(timeoutMs?)`

**Returns:** `Promise<Heading>`

#### `dismissHeadingCalibrationDisplay()`

iOS: hides the calibration overlay.

#### `startAltitudeUpdates(options?)`, `stopAltitudeUpdates()`

Barometric altitude as `altitude` events. iOS: `kind: 'relative'` (`relativeAltitude`, `pressure` in kPa) and, with `absolute: true` on iOS 15+, `kind: 'absolute'` (`altitude`, `accuracy`, `precision`). Android: `kind: 'pressure'` (`pressure`, `altitude` from the standard atmosphere, `relativeAltitude`). iOS emits `altitudeError` with `E_MOTION_PERMISSION_PENDING` / `E_MOTION_PERMISSION_DENIED` while Motion & Fitness access is missing.

### GNSS (Android)

#### `getGnssInfo()`

**Returns:** `Promise<GnssInfo>` — `{ supported, hardwareModelName?, yearOfHardware?, hasMeasurements, hasNavigationMessages, hasAntennaInfo, hasSatelliteBlocklist, hasMeasurementCorrections, hasLowPowerMode, hasSatellitePvt, hasAccumulatedDeltaRange }`. iOS returns `supported: false`.

#### `startGnssUpdates(options?)`, `stopGnssUpdates()`

Needs fine location. Options: `status` (default `true`: `gnssStatus`, `gnssFirstFix`, `gnssStarted`, `gnssStopped`), `nmea` (`nmea`), and the opt-in high-rate streams `measurements` (`gnssMeasurements`) and `navigationMessages` (`gnssNavigationMessage`). iOS rejects with `E_UNSUPPORTED`.

### Geocoding

#### `geocode(address, options?)`

#### `reverseGeocode({ latitude, longitude }, options?)`

**Options:** `locale` (BCP-47, default device locale), `maxResults` (default `5`), `provider` (iOS: `auto` = MapKit on iOS 26+ and `CLGeocoder` before, `clgeocoder`, `mapkit`).

**Returns:** `Promise<Address[]>` — `{ latitude?, longitude?, name?, streetNumber?, street?, subLocality?, locality?, subAdministrativeArea?, administrativeArea?, postalCode?, country?, isoCountryCode?, timeZone?, formattedAddress?, shortAddress?, areasOfInterest?, inlandWater?, ocean?, phone?, url? }`. No results resolve to an empty array.

#### `isGeocoderAvailable()`

Android `Geocoder.isPresent()`; always `true` on iOS.

### Mock Locations (Android)

#### `setMockLocationEnabled(enabled)`, `setMockLocation(location)`

Development builds only: the app must be the selected mock location app (Developer options, or `adb shell appops set <package> android:mock_location allow`) and declare `ACCESS_MOCK_LOCATION` in a debug manifest. Drives both the GPS test provider and fused mock mode. iOS rejects with `E_UNSUPPORTED` (use an Xcode GPX scheme).

### Utilities

#### `isLocationAvailable()`

Services on, permission granted, and (Android) a provider able to report.

#### `getCapabilities()`

**Returns:** `Promise<LocationCapabilities>` — `platform`, `osVersion`, `locationServicesEnabled`, `headingAvailable`, `significantChangeAvailable`, `visitsAvailable`, `regionMonitoringAvailable`, `maxMonitoredRegions`, `geofencingEngine` (`clmonitor`, `regionMonitoring`, `geofencingClient`, `none`), `beaconRangingAvailable`, `beaconMonitoringAvailable`, `liveUpdatesAvailable`, `serviceSessionAvailable`, `backgroundActivitySessionAvailable`, `temporaryFullAccuracyAvailable`, `altimeterAvailable`, `absoluteAltitudeAvailable`, `gnssStatusAvailable`, `nmeaAvailable`, `gnssMeasurementsAvailable`, `gnssNavigationMessagesAvailable`, `fusedLocationAvailable`, `fusedOrientationAvailable`, `geocoderAvailable`, `mockLocationAvailable`, `backgroundLocationModeEnabled`.

#### `getDistance(from, to)`, `getBearing(from, to)`, `getDestination(from, bearing, distance)`, `isPointWithinRadius(point, center, radius)`

Pure TypeScript geodesy: WGS84 Vincenty distance in metres (the model Android's `Location.distanceBetween` uses), initial bearing in degrees, destination point, and radius test.

### Events

Use `addEventListener(eventName, callback)`; it returns an unsubscribe function.

| Event | Payload |
| --- | --- |
| `location` | `{ watchId, locations }` for `watchPosition()` (the callback form is usually simpler). |
| `locationError` | `{ code, message, watchId?, source? }` |
| `locationAvailability` | Android: `{ watchId?, available }` |
| `locationUpdatesPaused`, `locationUpdatesResumed` | iOS auto-pause: `{ watchId?, source? }` |
| `liveUpdateDiagnostic` | iOS 17+: `{ watchId, stationary?, insufficientlyInUse?, locationUnavailable?, accuracyLimited?, authorizationDenied?, authorizationDeniedGlobally?, authorizationRestricted?, authorizationRequestInProgress?, serviceSessionRequired? }` |
| `authorizationChanged` | `PermissionStatus` when permission or the services switch changes (Android checks on resume). |
| `providerChanged` | `{ locationServicesEnabled, gpsEnabled?, networkEnabled? }` |
| `airplaneModeChanged` | Android: `{ airplaneModeOn }` |
| `backgroundLocation` | `{ locations }` from `startBackgroundUpdates()` |
| `significantLocationChange` | `{ locations }` |
| `visit` | iOS: `{ latitude, longitude, horizontalAccuracy, arrivalTimestamp?, departureTimestamp? }` |
| `backgroundLaunch` | `{ reason, timestamp }` — iOS `location` relaunch, Android `boot` / `packageReplaced` |
| `geofenceTransition` | `{ identifier, transition, kind, timestamp, location? }` |
| `geofenceState` | `{ identifier, state, kind }` |
| `geofenceError`, `geofenceExpired` | `{ code, message, identifier? }`, `{ identifier }` |
| `beaconsRanged`, `beaconRangingError` | iOS ranging results and failures |
| `heading`, `headingCalibrationNeeded` | Compass samples; calibration hint |
| `altitude`, `altitudeError` | Barometer samples and errors |
| `gnssStatus`, `gnssFirstFix`, `gnssStarted`, `gnssStopped`, `nmea`, `gnssMeasurements`, `gnssNavigationMessage` | Android GNSS streams |
| `serviceSessionDiagnostic`, `backgroundActivitySessionDiagnostic` | iOS 18 session diagnostics |
| `locationSettingsResolved` | Android: `{ satisfied }` after the settings dialog |

## 🔍 Troubleshooting

### Common Issues

1. **`E_LOCATION_PERMISSION_DENIED`**: request permission first; on Android also check that the manifest declares the permission.
2. **`E_LOCATION_TIMEOUT` indoors**: lower `accuracy` (for example `hundredMeters`) or pass `maximumAgeMs` to accept a recent cached fix.
3. **No background updates on iOS**: add the `location` background mode and request always access; `E_BACKGROUND_MODE_MISSING` means the mode is missing.
4. **Android 14 `E_FOREGROUND_SERVICE`**: declare `FOREGROUND_SERVICE_LOCATION` and grant location before starting the service while the app is visible.
5. **Geofences never fire on Android 10+**: geofencing needs both fine and background location.
6. **Android geocoding returns nothing**: `isGeocoderAvailable()` is `false` on devices without a geocoder backend (no Google Play services).
7. **No altitude events on iOS**: add `NSMotionUsageDescription` and allow Motion & Fitness.

### Xcode 27

- Apps built with Xcode 27 must adopt the UIScene lifecycle or they crash at launch on iOS 27 (Apple TN3187). Bare React Native apps need a `SceneDelegate`; see `example/ios/MunimLocationExample/AppDelegate.swift`. Expo SDK 57 apps set `expo-build-properties` → `ios.enableSceneSupport: true`.
- With the scene lifecycle a launch URL is in `connectionOptions.urlContexts`, not in `launchOptions`; the example passes it on so `Linking.getInitialURL()` works.
- `react-native-nitro-modules` 0.36/0.37 built with Xcode 27 crashes at launch on iOS 17 and older (margelo/nitro#1652); see the munim-bluetooth README for the `RuntimeError.hpp` patch until a fixed Nitro release ships.
- iOS 26/27-only symbols (MapKit geocoding requests, `maritime` activity) compile only with Xcode 26 / 27; older Xcode builds fall back to the previous APIs.

### Expo-Specific Issues

1. **Development Build Required**: This library requires a development build in Expo. Use `npx expo run:ios`, `npx expo run:android`, or an EAS development build. Expo Go is not supported.
2. **Permissions Not Working**: Make sure the config plugin is listed in `app.json` and rebuild the native project after changing its options.
3. **Build Errors**: Ensure you're using Expo SDK 50+ and have the latest Expo CLI.
4. **Nitro Modules**: Make sure you have `react-native-nitro-modules` installed.

### Example App

`example/` has a screen per feature group and a live event log. **Run checks** (or the deep link `munimlocationexample://checks`) exercises the API with whatever permission the app holds, without showing prompts, and writes `Documents/munim-location-checks.json` on iOS and `MUNIM_LOCATION_CHECKS` lines to logcat on Android.

## 👏 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details on how to submit pull requests, report issues, and contribute to the project.

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

<img alt="Star the Munim Technologies repo on GitHub to support the project" src="https://user-images.githubusercontent.com/9664363/185428788-d762fd5d-97b3-4f59-8db7-f72405be9677.gif" width="50%">
