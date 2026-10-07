# Location push (iOS)

iOS 15 added **location pushes**: your server sends an APNs push of type
`location`, and iOS wakes a small **Location Push Service Extension** in your
app, even when the app is not running, so it can take one fix and report it.
It is how apps like Find My answer "where are you now?" without tracking in
the background all day.

munim-location covers the app side:

- `startMonitoringLocationPushes()` starts monitoring and resolves with the
  hex APNs token your server sends `location` pushes to.
- `stopMonitoringLocationPushes()` stops it (call it on sign-out).
- The Expo config plugin option `iosLocationPushEntitlement: true` writes the
  `com.apple.developer.location.push` entitlement for the app.

The extension itself is native code that iOS runs in its own process, with no
React Native in it, so you add it to your project yourself. This folder ships
a ready-to-copy sample:

| File | What it is |
| --- | --- |
| [`location-push/LocationPushService.swift`](location-push/LocationPushService.swift) | The extension: reads the push, takes one fix, POSTs it as a munim-location `Location`. |
| [`location-push/Info.plist`](location-push/Info.plist) | The extension's `NSExtension` keys (bare projects; `@bacons/apple-targets` generates it). |
| [`location-push/expo-target.config.js`](location-push/expo-target.config.js) | Target config for `@bacons/apple-targets`. |

On Android there is no location push. Send a high-priority FCM data message
instead and answer it from JavaScript with `getCurrentPosition()` (see
[Android](#android-the-same-flow-with-fcm)). `startMonitoringLocationPushes()`
rejects with `E_UNSUPPORTED` there.

## Requirements

- iOS 15 or later, on a device (the simulator cannot receive APNs pushes).
- The `com.apple.developer.location.push` entitlement on **both** the app and
  the extension. Apple grants it on request for an Apple Developer team; until
  it is granted, provisioning profiles cannot include it and
  `startMonitoringLocationPushes()` rejects with `E_LOCATION_PUSH`.
- **Always** location access. iOS delivers pushes to the extension only once
  the person has granted Always, so register the token after
  `getPermissionStatus()` reports `background: true`.
- A server that can send APNs pushes with token-based (`.p8`) or certificate
  authentication.

## 1. The app: entitlement and token

### Expo

```json
{
  "expo": {
    "plugins": [
      ["munim-location", { "iosLocationPushEntitlement": true }]
    ]
  }
}
```

### React Native CLI

Add the entitlement to the app's `.entitlements` file (Xcode: Signing &
Capabilities → + Capability → Location Push Service Extension adds it once
Apple has granted it to your team):

```xml
<key>com.apple.developer.location.push</key>
<true/>
```

### JavaScript

```typescript
import { Platform } from 'react-native'
import {
  getPermissionStatus,
  startMonitoringLocationPushes,
  stopMonitoringLocationPushes,
  LocationError,
} from 'munim-location'

export async function registerLocationPushes() {
  if (Platform.OS !== 'ios') return
  const permission = await getPermissionStatus()
  if (!permission.background) return // pushes need Always access

  try {
    const token = await startMonitoringLocationPushes()
    await api.registerLocationPushToken({ token, platform: 'ios' })
  } catch (error) {
    if (error instanceof LocationError && error.code === 'E_LOCATION_PUSH') {
      // iOS refused, usually a missing entitlement in the provisioning profile.
    }
  }
}

export async function unregisterLocationPushes() {
  await api.unregisterLocationPushToken()
  stopMonitoringLocationPushes()
}
```

Call `startMonitoringLocationPushes()` on every launch (for example when the
app becomes active) and send the token to your server when it changed: like
other APNs tokens, it can change. `getCapabilities()` reports `locationPushAvailable` (iOS 15+).

## 2. The extension

### Expo with `@bacons/apple-targets`

1. Install it: `npx expo install @bacons/apple-targets` and add
   `"@bacons/apple-targets"` to `plugins` in `app.json`.
2. Create `targets/location-push/` and copy into it
   [`expo-target.config.js`](location-push/expo-target.config.js) and
   [`LocationPushService.swift`](location-push/LocationPushService.swift).
   The target type `location-push` generates the Info.plist, with
   `LocationPushService` as the principal class.
3. Run `npx expo prebuild -p ios` (or build with EAS). The extension gets
   bundle id `<app bundle id>.LocationPush` and the entitlement from the
   target config.

```js
// targets/location-push/expo-target.config.js
module.exports = {
  type: 'location-push',
  name: 'LocationPush',
  bundleIdentifier: '.LocationPush',
  deploymentTarget: '15.0',
  frameworks: ['CoreLocation'],
  entitlements: { 'com.apple.developer.location.push': true },
}
```

### React Native CLI (Xcode)

1. File → New → Target → **Location Push Service Extension**. Name it
   `LocationPush`, embed it in the app.
2. Replace the generated Swift file with
   [`LocationPushService.swift`](location-push/LocationPushService.swift)
   (keep the class name `LocationPushService`, or update
   `NSExtensionPrincipalClass` in the extension's Info.plist).
3. Add the Location Push capability (the entitlement) to the extension target
   too, and set its deployment target to iOS 15.0 or later.

## 3. The payload contract

The sample extension reads a `munimLocation` object from the push and posts
the fix back to the URL inside it. The fix uses the same field names as
munim-location's `Location` type (`latitude`, `longitude`,
`horizontalAccuracy`, `altitude`, `speed`, `course`, `timestamp` in epoch
milliseconds, ...), so the endpoint can take reports from the iOS extension
and from JavaScript (`getCurrentPosition()`) with the same parser.

Push (send to the token from `startMonitoringLocationPushes()`):

```
:method: POST
:path: /3/device/<hex token>
apns-push-type: location
apns-topic: <app bundle id>.location-query
apns-priority: 10
authorization: bearer <APNs provider JWT>
```

```json
{
  "aps": {},
  "munimLocation": {
    "reportUrl": "https://api.example.com/location-reports",
    "requestId": "req_123",
    "token": "single-use secret the server checks",
    "accuracy": "nearestTenMeters"
  }
}
```

`accuracy` is optional and takes a munim-location `LocationAccuracy` name
(`best`, `nearestTenMeters` (default), `hundredMeters`, `kilometer`, ...).

Report (`POST reportUrl`, `Content-Type: application/json`):

```json
{
  "requestId": "req_123",
  "token": "single-use secret the server checks",
  "location": {
    "latitude": 37.3349,
    "longitude": -122.009,
    "horizontalAccuracy": 12,
    "altitude": 21.4,
    "verticalAccuracy": 4,
    "speed": 0,
    "timestamp": 1791324753148,
    "provider": "corelocation",
    "isMock": false
  }
}
```

The extension has no access to the app's session or Keychain by default, so
the sample authenticates with the single-use `requestId` + `token` pair the
server put in the push. Make the token short-lived and single-use, and only
accept `https` report URLs (the sample rejects anything else).

## Android: the same flow with FCM

Android has no location push, but a high-priority FCM **data** message starts
the app's JavaScript in the background (for example through
`expo-notifications`' background task or `@react-native-firebase/messaging`'s
`setBackgroundMessageHandler`). Answer it with munim-location and post the
same report:

```typescript
import { getCurrentPosition, getLastKnownPosition, getPermissionStatus } from 'munim-location'

async function answerLocationRequest(request: { reportUrl: string; requestId: string; token: string }) {
  const permission = await getPermissionStatus()
  if (!permission.background) return
  // timeoutMs cancels the native request too, unlike a Promise.race wrapper.
  const location =
    (await getCurrentPosition({ accuracy: 'hundredMeters', timeoutMs: 10000 }).catch(() => undefined)) ??
    (await getLastKnownPosition())
  if (!location) return
  await fetch(request.reportUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId: request.requestId, token: request.token, location }),
  })
}
```

## Testing

- `startMonitoringLocationPushes()` resolving with a 64+ character hex token
  proves the entitlement is provisioned. The example app's **Run checks**
  covers it (`startMonitoringLocationPushes / stop`), and records a skip with
  iOS's reason when the entitlement is missing.
- To exercise the extension end to end, send a push to the token with the
  APNs development environment (`api.sandbox.push.apple.com`) for debug
  builds, with the device locked or the app terminated, and Always access
  granted. Watch the extension in Console.app (filter by its process name).
