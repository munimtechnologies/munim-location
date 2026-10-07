const {
  AndroidConfig,
  withAndroidManifest,
  withEntitlementsPlist,
  withInfoPlist,
} = require('@expo/config-plugins');

const DEFAULT_WHEN_IN_USE =
  'This app uses your location to show where you are and provide location-based features.';
const DEFAULT_ALWAYS =
  'This app uses your location in the background to keep location-based features working when the app is closed.';
const DEFAULT_MOTION =
  'This app uses the barometer to measure altitude changes.';

const NOTIFICATION_META = {
  androidNotificationTitle: 'com.munimlocation.notification_title',
  androidNotificationText: 'com.munimlocation.notification_text',
  androidNotificationChannelId: 'com.munimlocation.notification_channel_id',
  androidNotificationChannelName: 'com.munimlocation.notification_channel_name',
  androidNotificationIcon: 'com.munimlocation.notification_icon',
  androidNotificationColor: 'com.munimlocation.notification_color',
};

function ensureArray(parent, key) {
  if (!parent[key]) {
    parent[key] = [];
  }
  return parent[key];
}

function ensureAndroidPermission(manifest, name, extra = {}) {
  const permissions = ensureArray(manifest, 'uses-permission');
  const existing = permissions.find((entry) => entry.$?.['android:name'] === name);
  if (existing) {
    return;
  }
  permissions.push({ $: { 'android:name': name, ...extra } });
}

function ensureMetaData(application, name, value) {
  const metaData = ensureArray(application, 'meta-data');
  const existing = metaData.find((entry) => entry.$?.['android:name'] === name);
  if (existing) {
    existing.$['android:value'] = String(value);
    return;
  }
  metaData.push({ $: { 'android:name': name, 'android:value': String(value) } });
}

/**
 * @param {import('@expo/config-plugins').ExpoConfig} config
 * @param {{
 *   locationWhenInUsePermission?: string | false,
 *   locationAlwaysAndWhenInUsePermission?: string | false,
 *   locationAlwaysPermission?: string | false,
 *   temporaryFullAccuracyPurposes?: Record<string, string>,
 *   motionPermission?: string | false,
 *   isIosBackgroundLocationEnabled?: boolean,
 *   iosLocationPushEntitlement?: boolean,
 *   androidPermissions?: Array<'fine' | 'coarse' | 'background'> | false,
 *   isAndroidBackgroundLocationEnabled?: boolean,
 *   isAndroidForegroundServiceEnabled?: boolean,
 *   androidBootReceiver?: boolean,
 *   androidNotificationTitle?: string,
 *   androidNotificationText?: string,
 *   androidNotificationChannelId?: string,
 *   androidNotificationChannelName?: string,
 *   androidNotificationIcon?: string,
 *   androidNotificationColor?: string,
 * }} options
 */
function withMunimLocation(config, options = {}) {
  const iosBackground = options.isIosBackgroundLocationEnabled ?? false;
  const androidBackground = options.isAndroidBackgroundLocationEnabled ?? false;
  const foregroundService =
    options.isAndroidForegroundServiceEnabled ?? androidBackground;

  config = withInfoPlist(config, (pluginConfig) => {
    const infoPlist = pluginConfig.modResults;
    if (options.locationWhenInUsePermission !== false) {
      infoPlist.NSLocationWhenInUseUsageDescription =
        options.locationWhenInUsePermission ??
        infoPlist.NSLocationWhenInUseUsageDescription ??
        DEFAULT_WHEN_IN_USE;
    }
    if (options.locationAlwaysAndWhenInUsePermission !== false) {
      infoPlist.NSLocationAlwaysAndWhenInUseUsageDescription =
        options.locationAlwaysAndWhenInUsePermission ??
        infoPlist.NSLocationAlwaysAndWhenInUseUsageDescription ??
        DEFAULT_ALWAYS;
    }
    if (options.locationAlwaysPermission) {
      infoPlist.NSLocationAlwaysUsageDescription = options.locationAlwaysPermission;
    }
    if (options.temporaryFullAccuracyPurposes) {
      infoPlist.NSLocationTemporaryUsageDescriptionDictionary = {
        ...(infoPlist.NSLocationTemporaryUsageDescriptionDictionary ?? {}),
        ...options.temporaryFullAccuracyPurposes,
      };
    }
    if (options.motionPermission) {
      infoPlist.NSMotionUsageDescription = options.motionPermission;
    } else if (options.motionPermission === undefined && !infoPlist.NSMotionUsageDescription) {
      infoPlist.NSMotionUsageDescription = DEFAULT_MOTION;
    }
    if (iosBackground) {
      const modes = new Set(infoPlist.UIBackgroundModes ?? []);
      modes.add('location');
      infoPlist.UIBackgroundModes = Array.from(modes);
    }
    return pluginConfig;
  });

  if (options.iosLocationPushEntitlement) {
    // startMonitoringLocationPushes() needs this on the app; the Location
    // Push Service Extension target needs it too (see docs/location-push.md).
    config = withEntitlementsPlist(config, (pluginConfig) => {
      pluginConfig.modResults['com.apple.developer.location.push'] = true;
      return pluginConfig;
    });
  }

  return withAndroidManifest(config, (pluginConfig) => {
    const manifest = pluginConfig.modResults.manifest;
    const requested =
      options.androidPermissions === false
        ? []
        : options.androidPermissions ??
          (androidBackground ? ['fine', 'coarse', 'background'] : ['fine', 'coarse']);

    if (requested.includes('fine')) {
      ensureAndroidPermission(manifest, 'android.permission.ACCESS_FINE_LOCATION');
    }
    if (requested.includes('coarse') || requested.includes('fine')) {
      ensureAndroidPermission(manifest, 'android.permission.ACCESS_COARSE_LOCATION');
    }
    if (requested.includes('background') || androidBackground) {
      ensureAndroidPermission(manifest, 'android.permission.ACCESS_BACKGROUND_LOCATION');
    }
    if (foregroundService) {
      ensureAndroidPermission(manifest, 'android.permission.FOREGROUND_SERVICE');
      ensureAndroidPermission(manifest, 'android.permission.FOREGROUND_SERVICE_LOCATION');
      ensureAndroidPermission(manifest, 'android.permission.POST_NOTIFICATIONS');
    }

    const application = AndroidConfig.Manifest.getMainApplicationOrThrow(
      pluginConfig.modResults
    );
    if (options.androidBootReceiver) {
      // The library manifest declares the boot receiver; it only fires once
      // the app holds this permission.
      ensureAndroidPermission(manifest, 'android.permission.RECEIVE_BOOT_COMPLETED');
    }
    Object.entries(NOTIFICATION_META).forEach(([option, name]) => {
      if (options[option]) {
        ensureMetaData(application, name, options[option]);
      }
    });
    return pluginConfig;
  });
}

module.exports = withMunimLocation;
module.exports.default = withMunimLocation;
