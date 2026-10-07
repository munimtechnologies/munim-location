import { type HybridObject } from 'react-native-nitro-modules'

// Option structs in this spec use non-optional fields on purpose: Release
// builds can misread optional struct/enum arguments (margelo/nitro#1319).
// `src/index.ts` fills every default before calling into native code.

// ========== Permissions ==========

/** Location authorization, normalised across iOS and Android. */
export type AuthorizationStatus =
  | 'notDetermined'
  | 'restricted'
  | 'denied'
  | 'whenInUse'
  | 'always'

/** iOS accuracyAuthorization; Android fine (`full`) vs coarse (`reduced`). */
export type AccuracyAuthorization = 'full' | 'reduced'

/** Android runtime location permissions. */
export type AndroidLocationPermission = 'fine' | 'coarse' | 'background'

export interface PermissionStatus {
  status: AuthorizationStatus
  accuracy: AccuracyAuthorization
  /** Foreground ("when in use") access is granted. */
  foreground: boolean
  /** Background ("always") access is granted. */
  background: boolean
  /** Precise location is available (iOS full accuracy, Android fine). */
  precise: boolean
  /**
   * Whether a system prompt can still be shown. Android: false after
   * "don't ask again"; iOS: true only while the status is notDetermined (or
   * whenInUse, which can be upgraded to always once).
   */
  canAskAgain: boolean
  /** System-wide location services switch. */
  locationServicesEnabled: boolean
}

// ========== Location ==========

/** Requested accuracy (iOS desiredAccuracy; mapped to a priority on Android). */
export type LocationAccuracy =
  | 'bestForNavigation'
  | 'best'
  | 'nearestTenMeters'
  | 'hundredMeters'
  | 'kilometer'
  | 'threeKilometers'
  | 'reduced'

/** Android Fused Location Provider priority. `auto` derives it from accuracy. */
export type LocationPriority =
  | 'auto'
  | 'highAccuracy'
  | 'balanced'
  | 'lowPower'
  | 'passive'

/** Android location request granularity. */
export type LocationGranularity = 'permissionLevel' | 'fine' | 'coarse'

/** iOS CLActivityType (`maritime` needs iOS 27). */
export type ActivityType =
  | 'other'
  | 'automotiveNavigation'
  | 'fitness'
  | 'otherNavigation'
  | 'airborne'
  | 'maritime'

export interface Location {
  latitude: number
  longitude: number
  /** Altitude in metres. iOS: above mean sea level; Android: WGS84 ellipsoid. */
  altitude?: number
  /** iOS 15+: height above the WGS84 ellipsoid. */
  ellipsoidalAltitude?: number
  /** Android 14+: altitude above mean sea level. */
  mslAltitude?: number
  /** Android 14+: MSL altitude accuracy in metres. */
  mslAltitudeAccuracy?: number
  /** Horizontal accuracy radius in metres (68% confidence). */
  horizontalAccuracy: number
  verticalAccuracy?: number
  /** Metres per second. */
  speed?: number
  speedAccuracy?: number
  /** Course / bearing in degrees from true north. */
  course?: number
  courseAccuracy?: number
  /** Unix epoch milliseconds. */
  timestamp: number
  /** Android: SystemClock.elapsedRealtimeNanos of the fix. */
  elapsedRealtimeNanos?: number
  elapsedRealtimeUncertaintyNanos?: number
  /** iOS: building floor level, when known. */
  floor?: number
  /** Android provider (`gps`, `network`, `fused`, `passive`); iOS `corelocation`. */
  provider?: string
  /** True when the platform reports the fix as mocked or simulated. */
  isMock: boolean
  /** iOS 15+: CLLocationSourceInformation.isSimulatedBySoftware. */
  isSimulatedBySoftware?: boolean
  /** iOS 15+: CLLocationSourceInformation.isProducedByAccessory. */
  isProducedByAccessory?: boolean
  /** Android: satellites used in the fix, when reported. */
  satelliteCount?: number
}

export interface CurrentPositionOptions {
  accuracy: LocationAccuracy
  priority: LocationPriority
  /** Fail after this many milliseconds. */
  timeoutMs: number
  /** Accept a cached fix no older than this (0 = always fetch a new one). */
  maximumAgeMs: number
  granularity: LocationGranularity
  /** Android CurrentLocationRequest duration (ms). */
  durationMs: number
}

export interface LastKnownPositionOptions {
  /** Ignore cached fixes older than this (0 = any age). */
  maximumAgeMs: number
  /** Ignore cached fixes less accurate than this, in metres (0 = any). */
  requiredAccuracy: number
}

export interface WatchOptions {
  accuracy: LocationAccuracy
  priority: LocationPriority
  /** Minimum distance in metres between updates (0 = none). */
  distanceFilter: number
  /** Android desired interval (ms). */
  intervalMs: number
  /** Android minimum interval between updates (ms, 0 = default). */
  fastestIntervalMs: number
  /** Android batching: deliver updates late, at most this delay (ms). */
  maxUpdateDelayMs: number
  /** Android: accept cached fixes no older than this (ms, 0 = default). */
  minUpdateAgeMs: number
  /** Stop after this many updates (0 = unlimited). */
  maxUpdates: number
  /** Android: wait for an accurate fix before the first delivery. */
  waitForAccurateLocation: boolean
  granularity: LocationGranularity
  /** iOS activity type. */
  activityType: ActivityType
  /** iOS pausesLocationUpdatesAutomatically. */
  pausesLocationUpdatesAutomatically: boolean
  /** iOS 17+: use CLLocationUpdate.liveUpdates instead of a CLLocationManager. */
  useLiveUpdates: boolean
  /** iOS allowsBackgroundLocationUpdates (needs the `location` background mode). */
  allowsBackgroundLocationUpdates: boolean
  /** iOS showsBackgroundLocationIndicator. */
  showsBackgroundLocationIndicator: boolean
}

// ========== Services and settings ==========

export interface ProviderStatus {
  locationServicesEnabled: boolean
  gpsEnabled: boolean
  networkEnabled: boolean
  passiveEnabled: boolean
  /** Android 12+ `fused` LocationManager provider, or Google Play services. */
  fusedEnabled: boolean
  airplaneModeOn: boolean
  /** Android: every LocationManager provider name. */
  providers: string[]
}

export interface LocationSettingsResult {
  satisfied: boolean
  /** Android: a resolution dialog can fix the settings. */
  resolvable: boolean
  locationPresent: boolean
  locationUsable: boolean
  gpsPresent: boolean
  gpsUsable: boolean
  networkLocationPresent: boolean
  networkLocationUsable: boolean
  blePresent: boolean
  bleUsable: boolean
  statusCode: number
  message: string
}

/** iOS 18 CLServiceSession authorization requirement. */
export type ServiceSessionAuthorization = 'none' | 'whenInUse' | 'always'

// ========== Background ==========

/** Android background delivery mode. */
export type AndroidBackgroundMode = 'foregroundService' | 'pendingIntent'

export interface AndroidBackgroundOptions {
  mode: AndroidBackgroundMode
  notificationTitle: string
  notificationText: string
  notificationChannelId: string
  notificationChannelName: string
  /** Drawable or mipmap resource name for the notification icon. */
  notificationIconResourceName: string
  /** `#RRGGBB` accent colour, or empty. */
  notificationColor: string
  /** Restart tracking after reboot / app update (needs RECEIVE_BOOT_COMPLETED). */
  restartOnBoot: boolean
  /** Stop tracking when the user swipes the app away. */
  stopOnTaskRemoved: boolean
}

export interface BackgroundStatus {
  running: boolean
  /** `foregroundService`, `pendingIntent`, `ios`, or empty when stopped. */
  mode: string
  significantChanges: boolean
  visits: boolean
  geofenceCount: number
  /** Events persisted while no JavaScript listener was attached. */
  pendingEventCount: number
}

// ========== Geofencing and beacons ==========

export type GeofenceState = 'inside' | 'outside' | 'unknown'

export interface GeofenceRegion {
  identifier: string
  latitude: number
  longitude: number
  /** Metres. */
  radius: number
  notifyOnEntry: boolean
  notifyOnExit: boolean
  notifyOnDwell: boolean
  /** Dwell delay (ms) before a `dwell` transition. */
  loiteringDelayMs: number
  /** Android: remove after this many ms (0 = never). */
  expirationMs: number
  /** Emit `enter` right away when the device is already inside. */
  notifyOnStartIfInside: boolean
}

export interface BeaconConstraint {
  identifier: string
  uuid: string
  /** -1 = any major. */
  major: number
  /** -1 = any minor. */
  minor: number
}

// ========== Heading and altitude ==========

export type HeadingOrientation =
  | 'portrait'
  | 'portraitUpsideDown'
  | 'landscapeLeft'
  | 'landscapeRight'
  | 'faceUp'
  | 'faceDown'

export interface HeadingOptions {
  /** Minimum change in degrees between events (0 = every change). */
  headingFilter: number
  orientation: HeadingOrientation
  /** iOS: allow the system compass-calibration overlay. */
  showsCalibrationDisplay: boolean
  /** Android 13+: prefer FusedOrientationProviderClient when available. */
  useFusedOrientation: boolean
  /** Android sensor sampling period (ms). */
  samplingPeriodMs: number
}

export interface Heading {
  /** Degrees from magnetic north. */
  magneticHeading: number
  /** Degrees from true north (needs a location fix; -1 when unknown). */
  trueHeading: number
  /** Maximum deviation in degrees (-1 = invalid). */
  headingAccuracy: number
  /** Raw geomagnetic vector in microteslas (iOS). */
  x?: number
  y?: number
  z?: number
  timestamp: number
  /** `corelocation`, `rotationVector`, `geomagneticRotationVector`, or `fusedOrientation`. */
  source: string
}

// ========== GNSS ==========

export interface GnssOptions {
  status: boolean
  nmea: boolean
  /** Opt-in: raw GNSS measurements (high rate). */
  measurements: boolean
  /** Opt-in: decoded navigation message bits. */
  navigationMessages: boolean
}

export interface GnssInfo {
  supported: boolean
  hardwareModelName?: string
  yearOfHardware?: number
  hasMeasurements: boolean
  hasNavigationMessages: boolean
  hasAntennaInfo: boolean
  hasSatelliteBlocklist: boolean
  hasMeasurementCorrections: boolean
  hasLowPowerMode: boolean
  hasSatellitePvt: boolean
  hasAccumulatedDeltaRange: boolean
}

// ========== Geocoding ==========

/** iOS geocoder backend. `auto` uses MapKit on iOS 26+ and CLGeocoder before. */
export type GeocodingProvider = 'auto' | 'clgeocoder' | 'mapkit'

export interface GeocodeOptions {
  /** BCP-47 locale such as `en-US`, or empty for the device locale. */
  locale: string
  maxResults: number
  provider: GeocodingProvider
}

export interface Address {
  latitude?: number
  longitude?: number
  name?: string
  /** House number (iOS subThoroughfare / Android subThoroughfare). */
  streetNumber?: string
  /** Street name (thoroughfare). */
  street?: string
  subLocality?: string
  /** City. */
  locality?: string
  subAdministrativeArea?: string
  /** State or province. */
  administrativeArea?: string
  postalCode?: string
  country?: string
  isoCountryCode?: string
  timeZone?: string
  formattedAddress?: string
  shortAddress?: string
  areasOfInterest?: string[]
  inlandWater?: string
  ocean?: string
  phone?: string
  url?: string
}

// ========== Mock locations ==========

export interface MockLocation {
  latitude: number
  longitude: number
  altitude: number
  accuracy: number
  speed: number
  bearing: number
}

// ========== Capabilities ==========

export interface LocationCapabilities {
  platform: string
  osVersion: string
  locationServicesEnabled: boolean
  headingAvailable: boolean
  significantChangeAvailable: boolean
  visitsAvailable: boolean
  regionMonitoringAvailable: boolean
  maxMonitoredRegions: number
  /** `clmonitor`, `regionMonitoring`, `geofencingClient`, or `none`. */
  geofencingEngine: string
  beaconRangingAvailable: boolean
  beaconMonitoringAvailable: boolean
  liveUpdatesAvailable: boolean
  serviceSessionAvailable: boolean
  backgroundActivitySessionAvailable: boolean
  temporaryFullAccuracyAvailable: boolean
  altimeterAvailable: boolean
  absoluteAltitudeAvailable: boolean
  gnssStatusAvailable: boolean
  nmeaAvailable: boolean
  gnssMeasurementsAvailable: boolean
  gnssNavigationMessagesAvailable: boolean
  fusedLocationAvailable: boolean
  fusedOrientationAvailable: boolean
  geocoderAvailable: boolean
  mockLocationAvailable: boolean
  backgroundLocationModeEnabled: boolean
  /** iOS 15+: CLLocationManager.startMonitoringLocationPushes is available. */
  locationPushAvailable: boolean
}

export interface MunimLocation
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  // ---------- Events ----------
  /**
   * Single native event channel: `name` plus a JSON payload. Events raised
   * while no listener is attached (iOS background relaunch, Android headless
   * receivers) are persisted and delivered on the next attach.
   */
  setEventListener(listener: (name: string, payload: string) => void): void
  removeEventListener(): void
  /** JSON array of `{ name, payload, timestamp }` persisted background events. */
  getPendingBackgroundEvents(): string
  clearPendingBackgroundEvents(): void

  // ---------- Permissions ----------
  getPermissionStatus(): Promise<PermissionStatus>
  requestForegroundPermission(precise: boolean): Promise<PermissionStatus>
  requestBackgroundPermission(): Promise<PermissionStatus>
  requestTemporaryFullAccuracy(purposeKey: string): Promise<AccuracyAuthorization>
  shouldShowRationale(permission: AndroidLocationPermission): boolean
  openAppSettings(): Promise<boolean>
  openLocationSettings(): Promise<boolean>

  // ---------- Services ----------
  isLocationServicesEnabled(): Promise<boolean>
  getProviderStatus(): Promise<ProviderStatus>
  checkLocationSettings(
    priority: LocationPriority,
    needBle: boolean
  ): Promise<LocationSettingsResult>
  requestLocationSettingsResolution(
    priority: LocationPriority,
    needBle: boolean
  ): Promise<boolean>
  /** iOS 18+ CLServiceSession; returns a session id, or '' when unsupported. */
  startServiceSession(
    authorization: ServiceSessionAuthorization,
    fullAccuracyPurposeKey: string
  ): string
  stopServiceSession(sessionId: string): void
  /** iOS 17+ CLBackgroundActivitySession; returns an id, or '' when unsupported. */
  startBackgroundActivitySession(): string
  stopBackgroundActivitySession(sessionId: string): void

  // ---------- Positions ----------
  getCurrentPosition(options: CurrentPositionOptions): Promise<Location>
  getLastKnownPosition(
    options: LastKnownPositionOptions
  ): Promise<Location | undefined>
  watchPosition(options: WatchOptions): number
  clearWatch(watchId: number): void
  clearAllWatches(): void

  // ---------- Background ----------
  startBackgroundUpdates(
    options: WatchOptions,
    android: AndroidBackgroundOptions
  ): Promise<boolean>
  stopBackgroundUpdates(): Promise<void>
  startSignificantLocationChanges(): Promise<boolean>
  stopSignificantLocationChanges(): void
  startVisitMonitoring(): Promise<boolean>
  stopVisitMonitoring(): void
  getBackgroundStatus(): BackgroundStatus

  // ---------- Geofencing ----------
  addGeofence(region: GeofenceRegion): Promise<void>
  removeGeofence(identifier: string): Promise<void>
  removeAllGeofences(): Promise<void>
  getMonitoredGeofences(): Promise<GeofenceRegion[]>
  requestGeofenceState(identifier: string): Promise<GeofenceState>
  getMaxMonitoredGeofences(): number

  // ---------- Beacons (iOS) ----------
  startBeaconRanging(constraint: BeaconConstraint): void
  stopBeaconRanging(identifier: string): void
  startBeaconMonitoring(
    constraint: BeaconConstraint,
    notifyEntryStateOnDisplay: boolean
  ): Promise<void>
  stopBeaconMonitoring(identifier: string): void

  // ---------- Heading and altitude ----------
  startHeadingUpdates(options: HeadingOptions): void
  stopHeadingUpdates(): void
  getCurrentHeading(timeoutMs: number): Promise<Heading>
  dismissHeadingCalibrationDisplay(): void
  startAltitudeUpdates(absolute: boolean): void
  stopAltitudeUpdates(): void

  // ---------- GNSS (Android) ----------
  getGnssInfo(): Promise<GnssInfo>
  startGnssUpdates(options: GnssOptions): Promise<void>
  stopGnssUpdates(): void

  // ---------- Geocoding ----------
  isGeocoderAvailable(): boolean
  geocode(address: string, options: GeocodeOptions): Promise<Address[]>
  reverseGeocode(
    latitude: number,
    longitude: number,
    options: GeocodeOptions
  ): Promise<Address[]>

  // ---------- Mock locations (Android) ----------
  setMockLocationEnabled(enabled: boolean): Promise<boolean>
  setMockLocation(location: MockLocation): Promise<void>

  // ---------- Location push (iOS 15+) ----------
  /**
   * iOS 15+: starts monitoring APNs `location` pushes and resolves with the
   * hex APNs token. Rejects with E_UNSUPPORTED on Android and older iOS.
   */
  startMonitoringLocationPushes(): Promise<string>
  stopMonitoringLocationPushes(): void

  // ---------- Utilities ----------
  isLocationAvailable(): Promise<boolean>
  getCapabilities(): Promise<LocationCapabilities>
}
