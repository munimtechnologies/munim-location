## [0.2.0](https://github.com/munimtechnologies/munim-location/compare/v0.1.0...v0.2.0) (2026-10-07)

### ✨ Features

* expo-location parity helpers ([9e0f228](https://github.com/munimtechnologies/munim-location/commit/9e0f228a890599e7a1dc7a90681cd30d64c78230))
* **ios:** location push monitoring and iosLocationPushEntitlement plugin option ([918e7be](https://github.com/munimtechnologies/munim-location/commit/918e7be71a9bbbf043ad92d1e8dd25dc4a1427ee))

### 🐛 Bug Fixes

* **android:** ship consumer R8 rules for the service, headless task, and receivers ([6dfc184](https://github.com/munimtechnologies/munim-location/commit/6dfc18450ffc9e74f7b3b465bc11f1e8aa86e27d))
* **ios:** spell out location push errors and add aps-environment in the plugin ([58f588b](https://github.com/munimtechnologies/munim-location/commit/58f588b948e7b6888698d7f8b7ebea0cfcb1e993))

### 📚 Documentation

* location push guide with sample extension, and migrating from expo-location ([1fb60e4](https://github.com/munimtechnologies/munim-location/commit/1fb60e496ab070d80db116951aed88680434d5e2))

## 0.1.0 (2026-10-07)

### ✨ Features

* First release: permissions (foreground, background, precise and approximate, temporary full accuracy, Android settings resolution), one-shot and continuous positions (Fused Location Provider with a LocationManager fallback, iOS 17 live updates), background and headless tracking (iOS background mode, significant changes and visits; Android foreground service, PendingIntent delivery, Headless JS and restore after reboot), geofencing (CLMonitor and GeofencingClient), iBeacon ranging and monitoring, heading, barometric altitude, Android GNSS (satellites, NMEA, raw measurements), geocoding, mock locations for development builds, distance and bearing helpers, and an Expo config plugin
