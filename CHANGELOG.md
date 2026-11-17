## [0.4.1](https://github.com/munimtechnologies/munim-location/compare/v0.4.0...v0.4.1) (2026-11-17)

### 🛠️ Other changes

* **deps:** patch vulnerable dev dependencies (undici, addressable) ([#4](https://github.com/munimtechnologies/munim-location/issues/4)) ([e405949](https://github.com/munimtechnologies/munim-location/commit/e4059497a8f005bc033d01e11915f0fb9fa3afc8))

## [0.4.0](https://github.com/munimtechnologies/munim-location/compare/v0.3.1...v0.4.0) (2026-11-16)

### ✨ Features

* native location batching, pause when stationary, motion activity ([066ecc4](https://github.com/munimtechnologies/munim-location/commit/066ecc46bd7a30e61aa99652245fa6f2e6a89601))
* track background permission with canAskAgainBackground ([200dd15](https://github.com/munimtechnologies/munim-location/commit/200dd15d9e5796cf6696dc6fb26a606a15e59b4f))

### 🐛 Bug Fixes

* **android:** deliver the first fix when distanceFilter is set ([86c07f9](https://github.com/munimtechnologies/munim-location/commit/86c07f936c120f3afe69b7cddaa96a90d4e810ef))
* **android:** get a fix for Low (and Balanced) one-shots on a Wi-Fi-only phone ([#3](https://github.com/munimtechnologies/munim-location/issues/3)) ([c323831](https://github.com/munimtechnologies/munim-location/commit/c32383194c0aad5002af74ede72b83eb8b174e4c))
* **android:** give a new watch its first fix promptly ([e339017](https://github.com/munimtechnologies/munim-location/commit/e3390171e1bf2b2b75bb19652470194ae9efdbf2))
* lint errors and stale CocoaPods cache in CI ([7df608d](https://github.com/munimtechnologies/munim-location/commit/7df608d04edad7f68f2e381d3382c6a4a081ffd6))

### 🛠️ Other changes

* **deps:** React Native 0.88.1, Nitro 0.37.2, semantic-release 26, ESLint 10 ([29de586](https://github.com/munimtechnologies/munim-location/commit/29de5861c17180aa40acae9588a05ae758694513))
* **deps:** regenerate the lockfile from scratch ([f20b62f](https://github.com/munimtechnologies/munim-location/commit/f20b62f8cda6a850fd577209b25b54095124c0f3))
* regenerate nitrogen 0.37.2 output ([8127ac0](https://github.com/munimtechnologies/munim-location/commit/8127ac037e01099d2408d369de91f6b6a49ea20a))
* update the example Podfile.lock for React Native 0.88.1 and Nitro 0.37.2 ([f34d1e2](https://github.com/munimtechnologies/munim-location/commit/f34d1e26da2e5957a9ba5515a679c08479352568))

## [0.3.1](https://github.com/munimtechnologies/munim-location/compare/v0.3.0...v0.3.1) (2026-10-17)

### 🐛 Bug Fixes

* accept react-native-nitro-modules 0.36.5 again ([78f3617](https://github.com/munimtechnologies/munim-location/commit/78f36177ca1b0fcb9d2e0a5b49bf91e476c05ee0)), closes [margelo/nitro#1573](https://github.com/margelo/nitro/issues/1573)

## [0.3.0](https://github.com/munimtechnologies/munim-location/compare/v0.2.0...v0.3.0) (2026-10-17)

### ✨ Features

* React Native 0.88 and Nitro Modules 0.37.1 ([#1](https://github.com/munimtechnologies/munim-location/issues/1)) ([0fbe215](https://github.com/munimtechnologies/munim-location/commit/0fbe215a5bd2257b0b08e92de4c5509d1e3506af))

### 📚 Documentation

* **example:** describe the munim-location example ([2ddc2b6](https://github.com/munimtechnologies/munim-location/commit/2ddc2b66ba7a48ab5f8894da687010cfac1e4d66))

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
