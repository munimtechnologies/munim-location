# munim-location Example

This React Native app exercises every part of `munim-location` on a physical device, with a live event log.

## Screens

- Permissions: foreground, background and temporary full accuracy, plus the Android rationale and settings shortcuts
- Services: location services, provider status and the Android location settings dialog
- One-shot and Watch: current and last known positions, continuous updates and `Accuracy` presets
- Mock location: development builds on Android only
- Background and terminated delivery: background updates, significant changes, visits and the headless handler
- Geofences: add, list, query state and remove regions
- iBeacon: ranging and monitoring, iOS only
- Compass and Altimeter
- GNSS: satellites and NMEA, Android only
- Geocoding: forward and reverse

## Checks

The checks never run on launch. Press **Run checks**, or open the deep link:

```bash
# iOS (device)
xcrun devicectl device process launch --device <udid> --terminate-existing \
  --payload-url munimlocationexample://checks com.munimtech.munimlocationexample
# Android
adb shell am start -a android.intent.action.VIEW -d munimlocationexample://checks
```

Results go to `Documents/munim-location-checks.json` on iOS and to logcat on Android (tag `MUNIM_LOCATION_CHECKS`). Checks that need a permission you haven't granted are skipped and recorded, not failed.

## Run It

```bash
npm install
cd ios && pod install && cd ..
npm run ios      # or: npm run android
```

Location is best tested on a real device. Simulators and emulators only report the locations you feed them.
