// targets/location-push/expo-target.config.js for @bacons/apple-targets.
// Copy LocationPushService.swift and Info.plist from this folder next to it.
/** @type {import('@bacons/apple-targets/app.plugin').Config} */
module.exports = {
  type: 'location-push',
  name: 'LocationPush',
  bundleIdentifier: '.LocationPush',
  deploymentTarget: '15.0',
  frameworks: ['CoreLocation'],
  entitlements: {
    'com.apple.developer.location.push': true,
  },
}
