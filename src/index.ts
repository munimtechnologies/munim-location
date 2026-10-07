import { NitroModules } from 'react-native-nitro-modules'
import { AppRegistry, Platform } from 'react-native'
import type {
  MunimLocation as MunimLocationSpec,
  AccuracyAuthorization,
  ActivityType,
  Address,
  AndroidBackgroundMode,
  AndroidBackgroundOptions,
  AndroidLocationPermission,
  AuthorizationStatus,
  BackgroundStatus,
  BeaconConstraint,
  CurrentPositionOptions,
  GeocodeOptions,
  GeocodingProvider,
  GeofenceRegion,
  GeofenceState,
  GnssInfo,
  GnssOptions,
  Heading,
  HeadingOptions,
  HeadingOrientation,
  LastKnownPositionOptions,
  Location,
  LocationAccuracy,
  LocationCapabilities,
  LocationGranularity,
  LocationPriority,
  LocationSettingsResult,
  MockLocation,
  PermissionStatus,
  ProviderStatus,
  ServiceSessionAuthorization,
  WatchOptions,
} from './specs/munim-location.nitro'

export type {
  AccuracyAuthorization,
  ActivityType,
  Address,
  AndroidBackgroundMode,
  AndroidBackgroundOptions,
  AndroidLocationPermission,
  AuthorizationStatus,
  BackgroundStatus,
  BeaconConstraint,
  CurrentPositionOptions,
  GeocodeOptions,
  GeocodingProvider,
  GeofenceRegion,
  GeofenceState,
  GnssInfo,
  GnssOptions,
  Heading,
  HeadingOptions,
  HeadingOrientation,
  LastKnownPositionOptions,
  Location,
  LocationAccuracy,
  LocationCapabilities,
  LocationGranularity,
  LocationPriority,
  LocationSettingsResult,
  MockLocation,
  PermissionStatus,
  ProviderStatus,
  ServiceSessionAuthorization,
  WatchOptions,
}

const MunimLocation =
  NitroModules.createHybridObject<MunimLocationSpec>('MunimLocation')

/** Name of the Android Headless JS task that delivers background events. */
export const HEADLESS_TASK_NAME = 'MunimLocationHeadlessTask'

// ========== Errors ==========

export type LocationErrorCode =
  | 'E_LOCATION_PERMISSION_DENIED'
  | 'E_LOCATION_SERVICES_DISABLED'
  | 'E_LOCATION_TIMEOUT'
  | 'E_LOCATION_UNAVAILABLE'
  | 'E_LOCATION_SETTINGS'
  | 'E_BACKGROUND_MODE_MISSING'
  | 'E_FOREGROUND_SERVICE'
  | 'E_GEOFENCE_LIMIT'
  | 'E_GEOFENCE'
  | 'E_GEOCODE'
  | 'E_GEOCODE_NO_RESULT'
  | 'E_TEMPORARY_FULL_ACCURACY'
  | 'E_INVALID_UUID'
  | 'E_MOCK_LOCATION'
  | 'E_NO_ACTIVITY'
  | 'E_MOTION_PERMISSION_DENIED'
  | 'E_MOTION_PERMISSION_PENDING'
  | 'E_UNSUPPORTED'
  | 'E_LOCATION_ERROR'

/** Error thrown by every Promise in this package; `code` is stable. */
export class LocationError extends Error {
  readonly code: LocationErrorCode

  constructor(code: LocationErrorCode, message: string) {
    super(message)
    this.name = 'LocationError'
    this.code = code
  }
}

const ERROR_PATTERN = /(E_[A-Z_]+): ([\s\S]*)$/

export function toLocationError(error: unknown): LocationError {
  if (error instanceof LocationError) return error
  const message =
    error instanceof Error ? error.message : String(error ?? 'Unknown error')
  const match = ERROR_PATTERN.exec(message)
  if (match) {
    return new LocationError(
      match[1] as LocationErrorCode,
      (match[2] ?? '').trim()
    )
  }
  return new LocationError('E_LOCATION_ERROR', message)
}

async function call<T>(promise: () => Promise<T>): Promise<T> {
  try {
    return await promise()
  } catch (error) {
    throw toLocationError(error)
  }
}

// ========== Events ==========

/** A geofence or beacon-region transition. */
export interface GeofenceTransitionEvent {
  identifier: string
  transition: 'enter' | 'exit' | 'dwell'
  kind: 'circle' | 'beacon'
  timestamp: number
  location?: Location
}

export interface BeaconReading {
  uuid: string
  major: number
  minor: number
  proximity: 'immediate' | 'near' | 'far' | 'unknown'
  /** Estimated distance in metres (-1 when unknown). */
  accuracy: number
  rssi: number
  timestamp: number
}

export interface VisitEvent {
  latitude: number
  longitude: number
  horizontalAccuracy: number
  arrivalTimestamp?: number
  departureTimestamp?: number
}

export interface GnssSatellite {
  svid: number
  constellation:
    | 'gps'
    | 'sbas'
    | 'glonass'
    | 'qzss'
    | 'beidou'
    | 'galileo'
    | 'irnss'
    | 'unknown'
  cn0DbHz: number
  elevationDegrees: number
  azimuthDegrees: number
  usedInFix: boolean
  hasAlmanac: boolean
  hasEphemeris: boolean
  carrierFrequencyHz?: number
  basebandCn0DbHz?: number
}

export interface LocationErrorEvent {
  code: LocationErrorCode | string
  message: string
  watchId?: number
  source?: string
  identifier?: string
}

export type LocationEventMap = {
  /** Locations for a `watchPosition()` subscription. */
  location: { watchId: number; locations: Location[] }
  locationError: LocationErrorEvent
  /** Android LocationCallback.onLocationAvailability. */
  locationAvailability: { watchId?: number; available: boolean }
  /** iOS pausesLocationUpdatesAutomatically. */
  locationUpdatesPaused: { watchId?: number; source?: string }
  locationUpdatesResumed: { watchId?: number; source?: string }
  /** iOS 17+ CLLocationUpdate diagnostics (iOS 18 adds most flags). */
  liveUpdateDiagnostic: {
    watchId: number
    stationary?: boolean
    insufficientlyInUse?: boolean
    locationUnavailable?: boolean
    accuracyLimited?: boolean
    authorizationDenied?: boolean
    authorizationDeniedGlobally?: boolean
    authorizationRestricted?: boolean
    authorizationRequestInProgress?: boolean
    serviceSessionRequired?: boolean
  }
  authorizationChanged: PermissionStatus
  providerChanged: {
    locationServicesEnabled: boolean
    gpsEnabled?: boolean
    networkEnabled?: boolean
  }
  airplaneModeChanged: { airplaneModeOn: boolean }
  /** Locations from startBackgroundUpdates(). */
  backgroundLocation: { locations: Location[] }
  significantLocationChange: { locations: Location[] }
  visit: VisitEvent
  /** iOS relaunched the app for a location event. */
  backgroundLaunch: { reason: string; timestamp: number }
  geofenceTransition: GeofenceTransitionEvent
  geofenceState: {
    identifier: string
    state: GeofenceState
    kind: 'circle' | 'beacon'
  }
  geofenceError: LocationErrorEvent
  geofenceExpired: { identifier: string }
  beaconsRanged: { identifier: string; beacons: BeaconReading[] }
  beaconRangingError: LocationErrorEvent
  heading: Heading
  headingCalibrationNeeded: { timestamp: number }
  altitude: {
    kind: 'relative' | 'absolute' | 'pressure'
    /** Metres relative to the first sample (relative / pressure). */
    relativeAltitude?: number
    /** Kilopascals. */
    pressure?: number
    /** Metres above sea level (iOS 15 absolute altitude, Android pressure estimate). */
    altitude?: number
    accuracy?: number
    precision?: number
    timestamp: number
  }
  altitudeError: LocationErrorEvent
  gnssStatus: {
    satellites: GnssSatellite[]
    satelliteCount: number
    usedInFixCount: number
    timestamp: number
  }
  gnssStarted: Record<string, never>
  gnssStopped: Record<string, never>
  gnssFirstFix: { ttffMs: number }
  nmea: { message: string; timestamp: number }
  gnssMeasurements: {
    clock: Record<string, number | boolean>
    measurements: Array<Record<string, number | string | boolean>>
  }
  gnssNavigationMessage: {
    svid: number
    type: number
    status: number
    messageId: number
    submessageId: number
    data: string
  }
  serviceSessionDiagnostic: Record<string, boolean | string>
  backgroundActivitySessionDiagnostic: Record<string, boolean | string>
  locationSettingsResolved: { satisfied: boolean }
}

export type LocationEventName = keyof LocationEventMap

type AnyListener = (payload: unknown) => void

const listeners = new Map<string, Set<AnyListener>>()
const watchHandlers = new Map<
  number,
  {
    onLocation: (location: Location) => void
    onError?: (error: LocationError) => void
  }
>()

function dispatch(name: string, payloadText: string) {
  let payload: unknown
  try {
    payload = payloadText ? JSON.parse(payloadText) : {}
  } catch {
    payload = {}
  }
  if (name === 'location') {
    const event = payload as LocationEventMap['location']
    const handler = watchHandlers.get(event.watchId)
    if (handler) event.locations.forEach((location) => handler.onLocation(location))
  } else if (name === 'locationError') {
    const event = payload as LocationErrorEvent
    if (event.watchId !== undefined) {
      watchHandlers
        .get(event.watchId)
        ?.onError?.(
          new LocationError(event.code as LocationErrorCode, event.message)
        )
    }
  }
  listeners.get(name)?.forEach((listener) => {
    try {
      listener(payload)
    } catch (error) {
      console.error(`[munim-location] ${name} listener threw:`, error)
    }
  })
}

let nativeListenerAttached = false
function ensureNativeListener() {
  if (nativeListenerAttached) return
  nativeListenerAttached = true
  MunimLocation.setEventListener(dispatch)
}
ensureNativeListener()

/**
 * Subscribes to a location event. Returns an unsubscribe function.
 */
export function addEventListener<EventName extends LocationEventName>(
  eventName: EventName,
  callback: (payload: LocationEventMap[EventName]) => void
): () => void {
  ensureNativeListener()
  let set = listeners.get(eventName)
  if (!set) {
    set = new Set()
    listeners.set(eventName, set)
  }
  const listener = callback as AnyListener
  set.add(listener)
  return () => {
    listeners.get(eventName)?.delete(listener)
  }
}

// ========== Background handler ==========

/** Events persisted while no JavaScript was running. */
export const BACKGROUND_EVENT_NAMES = [
  'backgroundLocation',
  'significantLocationChange',
  'visit',
  'geofenceTransition',
  'geofenceExpired',
  'geofenceError',
  'backgroundLaunch',
  'locationUpdatesPaused',
  'locationUpdatesResumed',
] as const

export type BackgroundEventName = (typeof BACKGROUND_EVENT_NAMES)[number]

export type BackgroundEvent = {
  [K in BackgroundEventName]: {
    name: K
    payload: LocationEventMap[K]
    timestamp: number
    /** True when the event was replayed from the persisted queue. */
    replayed: boolean
    /** True when Android delivered it through the Headless JS task. */
    headless: boolean
  }
}[BackgroundEventName]

export type BackgroundHandler = (event: BackgroundEvent) => void | Promise<void>

let backgroundHandler: BackgroundHandler | null = null
let backgroundUnsubscribers: Array<() => void> = []
let headlessRegistered = false

async function runBackgroundHandler(event: BackgroundEvent) {
  const handler = backgroundHandler
  if (!handler) return
  try {
    await handler(event)
  } catch (error) {
    console.error('[munim-location] background handler threw:', error)
  }
}

/**
 * Registers the handler for background-origin events (background updates,
 * significant changes, visits, geofence transitions).
 *
 * Call it at the top level of your entry file (`index.js`), not inside a
 * component: on Android it also registers the Headless JS task that runs
 * when the app process was started only to deliver an event, and on iOS it
 * replays events persisted while the app was relaunched in the background.
 */
export function registerBackgroundHandler(handler: BackgroundHandler): () => void {
  backgroundHandler = handler
  backgroundUnsubscribers.forEach((unsubscribe) => unsubscribe())
  backgroundUnsubscribers = BACKGROUND_EVENT_NAMES.map((name) =>
    addEventListener(name, (payload) => {
      runBackgroundHandler({
        name,
        payload,
        timestamp: Date.now(),
        replayed: false,
        headless: false,
      } as BackgroundEvent)
    })
  )

  if (Platform.OS === 'android' && !headlessRegistered) {
    headlessRegistered = true
    AppRegistry.registerHeadlessTask(
      HEADLESS_TASK_NAME,
      () => async (data: { name?: string; payload?: string; timestamp?: number }) => {
        if (!data?.name) return
        let payload: unknown = {}
        try {
          payload = data.payload ? JSON.parse(data.payload) : {}
        } catch {
          payload = {}
        }
        await runBackgroundHandler({
          name: data.name,
          payload,
          timestamp: data.timestamp ?? Date.now(),
          replayed: false,
          headless: true,
        } as BackgroundEvent)
      }
    )
  }

  replayPendingBackgroundEvents().catch(() => {})

  return () => {
    if (backgroundHandler === handler) backgroundHandler = null
    backgroundUnsubscribers.forEach((unsubscribe) => unsubscribe())
    backgroundUnsubscribers = []
  }
}

async function replayPendingBackgroundEvents() {
  const pending = getPendingBackgroundEvents()
  if (pending.length === 0) return
  MunimLocation.clearPendingBackgroundEvents()
  for (const event of pending) {
    await runBackgroundHandler({ ...event, replayed: true, headless: false } as BackgroundEvent)
  }
}

/** Events persisted while no JavaScript listener was attached. */
export function getPendingBackgroundEvents(): Array<{
  name: BackgroundEventName
  payload: unknown
  timestamp: number
}> {
  try {
    const raw = JSON.parse(MunimLocation.getPendingBackgroundEvents()) as Array<{
      name: BackgroundEventName
      payload: string
      timestamp: number
    }>
    return raw.map((event) => {
      let payload: unknown = {}
      try {
        payload = JSON.parse(event.payload)
      } catch {
        payload = {}
      }
      return { name: event.name, payload, timestamp: event.timestamp }
    })
  } catch {
    return []
  }
}

export function clearPendingBackgroundEvents(): void {
  MunimLocation.clearPendingBackgroundEvents()
}

// ========== Permissions ==========

export function getPermissionStatus(): Promise<PermissionStatus> {
  return call(() => MunimLocation.getPermissionStatus())
}

/**
 * Requests foreground ("when in use") location access. On Android
 * `precise: false` asks for ACCESS_COARSE_LOCATION only.
 */
export function requestForegroundPermission(
  options: { precise?: boolean } = {}
): Promise<PermissionStatus> {
  return call(() =>
    MunimLocation.requestForegroundPermission(options.precise ?? true)
  )
}

/**
 * Requests background ("always") access. iOS: the provisional-always flow
 * when nothing was granted yet, otherwise the one-time upgrade prompt.
 * Android 10: the system dialog; Android 11+: the app's location settings
 * page (the system shows it for this request). Grant foreground access first.
 */
export function requestBackgroundPermission(): Promise<PermissionStatus> {
  return call(() => MunimLocation.requestBackgroundPermission())
}

/** Requests foreground access and then, if asked for, background access. */
export async function requestPermission(
  options: { background?: boolean; precise?: boolean } = {}
): Promise<PermissionStatus> {
  const foreground = await requestForegroundPermission(options)
  if (!options.background || !foreground.foreground || foreground.background) {
    return foreground
  }
  return requestBackgroundPermission()
}

/**
 * iOS 14+: temporarily upgrade reduced accuracy to full accuracy for this
 * session. `purposeKey` is a key of `NSLocationTemporaryUsageDescriptionDictionary`.
 * Android resolves with the current accuracy.
 */
export function requestTemporaryFullAccuracy(
  purposeKey: string
): Promise<AccuracyAuthorization> {
  return call(() => MunimLocation.requestTemporaryFullAccuracy(purposeKey))
}

/** Android shouldShowRequestPermissionRationale; always false on iOS. */
export function shouldShowRationale(
  permission: AndroidLocationPermission = 'fine'
): boolean {
  return MunimLocation.shouldShowRationale(permission)
}

export function openAppSettings(): Promise<boolean> {
  return call(() => MunimLocation.openAppSettings())
}

/** Android: system Location settings. iOS: the app's Settings page. */
export function openLocationSettings(): Promise<boolean> {
  return call(() => MunimLocation.openLocationSettings())
}

// ========== Services ==========

export function isLocationServicesEnabled(): Promise<boolean> {
  return call(() => MunimLocation.isLocationServicesEnabled())
}

export function getProviderStatus(): Promise<ProviderStatus> {
  return call(() => MunimLocation.getProviderStatus())
}

export interface LocationSettingsOptions {
  priority?: LocationPriority
  needBle?: boolean
}

/** Android SettingsClient.checkLocationSettings; iOS reports services + permission. */
export function checkLocationSettings(
  options: LocationSettingsOptions = {}
): Promise<LocationSettingsResult> {
  return call(() =>
    MunimLocation.checkLocationSettings(
      options.priority ?? 'highAccuracy',
      options.needBle ?? false
    )
  )
}

/**
 * Android: shows the Google Play services "turn on location / improve
 * accuracy" dialog and resolves whether the settings are now satisfied.
 * iOS: resolves whether location is usable (iOS has no resolution dialog).
 */
export function requestLocationSettingsResolution(
  options: LocationSettingsOptions = {}
): Promise<boolean> {
  return call(() =>
    MunimLocation.requestLocationSettingsResolution(
      options.priority ?? 'highAccuracy',
      options.needBle ?? false
    )
  )
}

export interface ServiceSession {
  /** Empty when the platform does not support the session type. */
  id: string
  supported: boolean
  stop(): void
}

/**
 * iOS 18+ CLServiceSession: declares that the app needs location now
 * (`whenInUse`/`always`), optionally with full accuracy. Android returns an
 * unsupported session.
 */
export function startServiceSession(
  options: {
    authorization?: ServiceSessionAuthorization
    fullAccuracyPurposeKey?: string
  } = {}
): ServiceSession {
  const id = MunimLocation.startServiceSession(
    options.authorization ?? 'whenInUse',
    options.fullAccuracyPurposeKey ?? ''
  )
  return {
    id,
    supported: id !== '',
    stop: () => {
      if (id) MunimLocation.stopServiceSession(id)
    },
  }
}

/**
 * iOS 17+ CLBackgroundActivitySession: keeps location sessions alive in the
 * background with the blue indicator. Start it while the app is in the
 * foreground. Android returns an unsupported session (use background updates).
 */
export function startBackgroundActivitySession(): ServiceSession {
  const id = MunimLocation.startBackgroundActivitySession()
  return {
    id,
    supported: id !== '',
    stop: () => {
      if (id) MunimLocation.stopBackgroundActivitySession(id)
    },
  }
}

// ========== Positions ==========

const DEFAULT_CURRENT: CurrentPositionOptions = {
  accuracy: 'best',
  priority: 'auto',
  timeoutMs: 30000,
  maximumAgeMs: 0,
  granularity: 'permissionLevel',
  durationMs: 0,
}

/** One-shot position. Rejects with `E_LOCATION_TIMEOUT` when nothing arrives. */
export function getCurrentPosition(
  options: Partial<CurrentPositionOptions> = {}
): Promise<Location> {
  const merged = { ...DEFAULT_CURRENT, ...options }
  if (!merged.durationMs) merged.durationMs = merged.timeoutMs
  return call(() => MunimLocation.getCurrentPosition(merged))
}

/** The most recent cached fix, or `undefined`. Never turns on GPS. */
export function getLastKnownPosition(
  options: Partial<LastKnownPositionOptions> = {}
): Promise<Location | undefined> {
  return call(() =>
    MunimLocation.getLastKnownPosition({
      maximumAgeMs: options.maximumAgeMs ?? 0,
      requiredAccuracy: options.requiredAccuracy ?? 0,
    })
  )
}

const DEFAULT_WATCH: WatchOptions = {
  accuracy: 'best',
  priority: 'auto',
  distanceFilter: 0,
  intervalMs: 5000,
  fastestIntervalMs: 0,
  maxUpdateDelayMs: 0,
  minUpdateAgeMs: 0,
  maxUpdates: 0,
  waitForAccurateLocation: false,
  granularity: 'permissionLevel',
  activityType: 'other',
  pausesLocationUpdatesAutomatically: false,
  useLiveUpdates: false,
  allowsBackgroundLocationUpdates: false,
  showsBackgroundLocationIndicator: true,
}

/**
 * Continuous updates. Returns a watch id for `clearWatch()`.
 */
export function watchPosition(
  onLocation: (location: Location) => void,
  onError?: (error: LocationError) => void,
  options: Partial<WatchOptions> = {}
): number {
  ensureNativeListener()
  const id = MunimLocation.watchPosition({ ...DEFAULT_WATCH, ...options })
  watchHandlers.set(id, { onLocation, onError })
  return id
}

export function clearWatch(watchId: number): void {
  watchHandlers.delete(watchId)
  MunimLocation.clearWatch(watchId)
}

export function clearAllWatches(): void {
  watchHandlers.clear()
  MunimLocation.clearAllWatches()
}

// ========== Background ==========

export interface BackgroundUpdateOptions extends Partial<WatchOptions> {
  android?: Partial<AndroidBackgroundOptions>
}

// Empty notification fields fall back to the manifest meta-data written by
// the Expo config plugin, then to built-in defaults ("Location tracking").
const DEFAULT_ANDROID_BACKGROUND: AndroidBackgroundOptions = {
  mode: 'foregroundService',
  notificationTitle: '',
  notificationText: '',
  notificationChannelId: '',
  notificationChannelName: '',
  notificationIconResourceName: '',
  notificationColor: '',
  restartOnBoot: false,
  stopOnTaskRemoved: false,
}

/**
 * Starts location updates that continue while the app is backgrounded and
 * deliver `backgroundLocation` events (and the background handler).
 * iOS: needs the `location` UIBackgroundModes entry. Android: a `location`
 * foreground service with a notification, or PendingIntent delivery.
 */
export function startBackgroundUpdates(
  options: BackgroundUpdateOptions = {}
): Promise<boolean> {
  const { android, ...watch } = options
  return call(() =>
    MunimLocation.startBackgroundUpdates(
      {
        ...DEFAULT_WATCH,
        allowsBackgroundLocationUpdates: true,
        intervalMs: 10000,
        ...watch,
      },
      { ...DEFAULT_ANDROID_BACKGROUND, ...android }
    )
  )
}

export function stopBackgroundUpdates(): Promise<void> {
  return call(() => MunimLocation.stopBackgroundUpdates())
}

/**
 * iOS significant-change monitoring (relaunches a terminated app). Android
 * uses a low-power PendingIntent request (~500 m / 5 min) as the equivalent.
 */
export function startSignificantLocationChanges(): Promise<boolean> {
  return call(() => MunimLocation.startSignificantLocationChanges())
}

export function stopSignificantLocationChanges(): void {
  MunimLocation.stopSignificantLocationChanges()
}

/** iOS CLVisit monitoring. Android rejects with `E_UNSUPPORTED`. */
export function startVisitMonitoring(): Promise<boolean> {
  return call(() => MunimLocation.startVisitMonitoring())
}

export function stopVisitMonitoring(): void {
  MunimLocation.stopVisitMonitoring()
}

export function getBackgroundStatus(): BackgroundStatus {
  return MunimLocation.getBackgroundStatus()
}

// ========== Geofencing ==========

export interface GeofenceInput
  extends Pick<GeofenceRegion, 'identifier' | 'latitude' | 'longitude' | 'radius'>,
    Partial<Omit<GeofenceRegion, 'identifier' | 'latitude' | 'longitude' | 'radius'>> {}

/** Starts monitoring a circular region. Re-adding an identifier replaces it. */
export function addGeofence(region: GeofenceInput): Promise<void> {
  return call(() =>
    MunimLocation.addGeofence({
      notifyOnEntry: true,
      notifyOnExit: true,
      notifyOnDwell: false,
      loiteringDelayMs: 30000,
      expirationMs: 0,
      notifyOnStartIfInside: false,
      ...region,
    })
  )
}

export async function addGeofences(regions: GeofenceInput[]): Promise<void> {
  for (const region of regions) {
    await addGeofence(region)
  }
}

export function removeGeofence(identifier: string): Promise<void> {
  return call(() => MunimLocation.removeGeofence(identifier))
}

export function removeAllGeofences(): Promise<void> {
  return call(() => MunimLocation.removeAllGeofences())
}

export function getMonitoredGeofences(): Promise<GeofenceRegion[]> {
  return call(() => MunimLocation.getMonitoredGeofences())
}

export function requestGeofenceState(identifier: string): Promise<GeofenceState> {
  return call(() => MunimLocation.requestGeofenceState(identifier))
}

/** iOS 20 (shared with beacon regions), Android 100. */
export function getMaxMonitoredGeofences(): number {
  return MunimLocation.getMaxMonitoredGeofences()
}

// ========== Beacons (iOS) ==========

export interface BeaconInput {
  identifier: string
  uuid: string
  major?: number
  minor?: number
}

function beaconConstraint(input: BeaconInput): BeaconConstraint {
  return {
    identifier: input.identifier,
    uuid: input.uuid,
    major: input.major ?? -1,
    minor: input.minor ?? -1,
  }
}

/** iOS iBeacon ranging; results arrive as `beaconsRanged` events. */
export function startBeaconRanging(beacon: BeaconInput): void {
  MunimLocation.startBeaconRanging(beaconConstraint(beacon))
}

export function stopBeaconRanging(identifier: string): void {
  MunimLocation.stopBeaconRanging(identifier)
}

/** iOS beacon-region monitoring; transitions arrive as `geofenceTransition` with `kind: 'beacon'`. */
export function startBeaconMonitoring(
  beacon: BeaconInput & { notifyEntryStateOnDisplay?: boolean }
): Promise<void> {
  return call(() =>
    MunimLocation.startBeaconMonitoring(
      beaconConstraint(beacon),
      beacon.notifyEntryStateOnDisplay ?? false
    )
  )
}

export function stopBeaconMonitoring(identifier: string): void {
  MunimLocation.stopBeaconMonitoring(identifier)
}

// ========== Heading and altitude ==========

const DEFAULT_HEADING: HeadingOptions = {
  headingFilter: 1,
  orientation: 'portrait',
  showsCalibrationDisplay: true,
  useFusedOrientation: true,
  samplingPeriodMs: 100,
}

/** Compass updates as `heading` events. */
export function startHeadingUpdates(options: Partial<HeadingOptions> = {}): void {
  MunimLocation.startHeadingUpdates({ ...DEFAULT_HEADING, ...options })
}

export function stopHeadingUpdates(): void {
  MunimLocation.stopHeadingUpdates()
}

export function getCurrentHeading(timeoutMs = 5000): Promise<Heading> {
  return call(() => MunimLocation.getCurrentHeading(timeoutMs))
}

/** iOS: hide the compass-calibration overlay. */
export function dismissHeadingCalibrationDisplay(): void {
  MunimLocation.dismissHeadingCalibrationDisplay()
}

/**
 * Barometric altitude as `altitude` events. iOS CMAltimeter (relative, plus
 * absolute on iOS 15+ when `absolute` is true; needs NSMotionUsageDescription);
 * Android pressure sensor.
 */
export function startAltitudeUpdates(options: { absolute?: boolean } = {}): void {
  MunimLocation.startAltitudeUpdates(options.absolute ?? false)
}

export function stopAltitudeUpdates(): void {
  MunimLocation.stopAltitudeUpdates()
}

// ========== GNSS (Android) ==========

export function getGnssInfo(): Promise<GnssInfo> {
  return call(() => MunimLocation.getGnssInfo())
}

/**
 * Android GNSS status (`gnssStatus`, `gnssFirstFix`), NMEA (`nmea`), and the
 * opt-in raw measurement / navigation message streams. Needs fine location.
 */
export function startGnssUpdates(options: Partial<GnssOptions> = {}): Promise<void> {
  return call(() =>
    MunimLocation.startGnssUpdates({
      status: options.status ?? true,
      nmea: options.nmea ?? false,
      measurements: options.measurements ?? false,
      navigationMessages: options.navigationMessages ?? false,
    })
  )
}

export function stopGnssUpdates(): void {
  MunimLocation.stopGnssUpdates()
}

// ========== Geocoding ==========

function geocodeOptions(options: Partial<GeocodeOptions>): GeocodeOptions {
  return {
    locale: options.locale ?? '',
    maxResults: options.maxResults ?? 5,
    provider: options.provider ?? 'auto',
  }
}

export function isGeocoderAvailable(): boolean {
  return MunimLocation.isGeocoderAvailable()
}

/** Forward geocoding: address text to structured results with coordinates. */
export function geocode(
  address: string,
  options: Partial<GeocodeOptions> = {}
): Promise<Address[]> {
  return call(() => MunimLocation.geocode(address, geocodeOptions(options)))
}

export function reverseGeocode(
  coordinate: { latitude: number; longitude: number },
  options: Partial<GeocodeOptions> = {}
): Promise<Address[]> {
  return call(() =>
    MunimLocation.reverseGeocode(
      coordinate.latitude,
      coordinate.longitude,
      geocodeOptions(options)
    )
  )
}

// ========== Mock locations (Android) ==========

/**
 * Android development builds: turn the app's test provider on or off.
 * Needs the app selected as the mock location app in Developer options
 * (or `adb shell appops set <package> android:mock_location allow`).
 */
export function setMockLocationEnabled(enabled: boolean): Promise<boolean> {
  return call(() => MunimLocation.setMockLocationEnabled(enabled))
}

export function setMockLocation(
  location: Pick<MockLocation, 'latitude' | 'longitude'> & Partial<MockLocation>
): Promise<void> {
  return call(() =>
    MunimLocation.setMockLocation({
      altitude: 0,
      accuracy: 5,
      speed: 0,
      bearing: 0,
      ...location,
    })
  )
}

// ========== Utilities ==========

/** Services on, permission granted, and (Android) a provider able to report. */
export function isLocationAvailable(): Promise<boolean> {
  return call(() => MunimLocation.isLocationAvailable())
}

export function getCapabilities(): Promise<LocationCapabilities> {
  return call(() => MunimLocation.getCapabilities())
}

export interface Coordinate {
  latitude: number
  longitude: number
}

const EARTH_RADIUS = 6371008.8
const WGS84_A = 6378137
const WGS84_F = 1 / 298.257223563
const WGS84_B = WGS84_A * (1 - WGS84_F)
const toRadians = (degrees: number) => (degrees * Math.PI) / 180
const toDegrees = (radians: number) => (radians * 180) / Math.PI

function haversine(from: Coordinate, to: Coordinate): number {
  const dLat = toRadians(to.latitude - from.latitude)
  const dLon = toRadians(to.longitude - from.longitude)
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(from.latitude)) *
      Math.cos(toRadians(to.latitude)) *
      Math.sin(dLon / 2) ** 2
  return 2 * EARTH_RADIUS * Math.asin(Math.min(1, Math.sqrt(a)))
}

/**
 * Distance in metres on the WGS84 ellipsoid (Vincenty inverse formula, the
 * same model as Android's Location.distanceBetween), falling back to the
 * haversine great-circle distance for nearly antipodal points.
 */
export function getDistance(from: Coordinate, to: Coordinate): number {
  const L = toRadians(to.longitude - from.longitude)
  const U1 = Math.atan((1 - WGS84_F) * Math.tan(toRadians(from.latitude)))
  const U2 = Math.atan((1 - WGS84_F) * Math.tan(toRadians(to.latitude)))
  const sinU1 = Math.sin(U1)
  const cosU1 = Math.cos(U1)
  const sinU2 = Math.sin(U2)
  const cosU2 = Math.cos(U2)
  let lambda = L
  for (let iteration = 0; iteration < 200; iteration++) {
    const sinLambda = Math.sin(lambda)
    const cosLambda = Math.cos(lambda)
    const sinSigma = Math.sqrt(
      (cosU2 * sinLambda) ** 2 + (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda) ** 2
    )
    if (sinSigma === 0) return 0
    const cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
    const sigma = Math.atan2(sinSigma, cosSigma)
    const sinAlpha = (cosU1 * cosU2 * sinLambda) / sinSigma
    const cosSqAlpha = 1 - sinAlpha * sinAlpha
    const cos2SigmaM =
      cosSqAlpha !== 0 ? cosSigma - (2 * sinU1 * sinU2) / cosSqAlpha : 0
    const C = (WGS84_F / 16) * cosSqAlpha * (4 + WGS84_F * (4 - 3 * cosSqAlpha))
    const previous = lambda
    lambda =
      L +
      (1 - C) *
        WGS84_F *
        sinAlpha *
        (sigma +
          C *
            sinSigma *
            (cos2SigmaM + C * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
    if (Math.abs(lambda - previous) < 1e-12) {
      const uSq = (cosSqAlpha * (WGS84_A ** 2 - WGS84_B ** 2)) / WGS84_B ** 2
      const A = 1 + (uSq / 16384) * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
      const B = (uSq / 1024) * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
      const deltaSigma =
        B *
        sinSigma *
        (cos2SigmaM +
          (B / 4) *
            (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
              (B / 6) *
                cos2SigmaM *
                (-3 + 4 * sinSigma * sinSigma) *
                (-3 + 4 * cos2SigmaM * cos2SigmaM)))
      return WGS84_B * A * (sigma - deltaSigma)
    }
  }
  return haversine(from, to)
}

/** Initial great-circle bearing from `from` to `to`, degrees clockwise from true north. */
export function getBearing(from: Coordinate, to: Coordinate): number {
  const phi1 = toRadians(from.latitude)
  const phi2 = toRadians(to.latitude)
  const dLon = toRadians(to.longitude - from.longitude)
  const y = Math.sin(dLon) * Math.cos(phi2)
  const x =
    Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon)
  return (toDegrees(Math.atan2(y, x)) + 360) % 360
}

/** Point reached travelling `distance` metres from `from` on `bearing` (spherical model). */
export function getDestination(
  from: Coordinate,
  bearing: number,
  distance: number
): Coordinate {
  const delta = distance / EARTH_RADIUS
  const theta = toRadians(bearing)
  const phi1 = toRadians(from.latitude)
  const lambda1 = toRadians(from.longitude)
  const phi2 = Math.asin(
    Math.sin(phi1) * Math.cos(delta) + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta)
  )
  const lambda2 =
    lambda1 +
    Math.atan2(
      Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
      Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2)
    )
  return {
    latitude: toDegrees(phi2),
    longitude: ((toDegrees(lambda2) + 540) % 360) - 180,
  }
}

/** True when `point` lies within `radius` metres of `center`. */
export function isPointWithinRadius(
  point: Coordinate,
  center: Coordinate,
  radius: number
): boolean {
  return getDistance(point, center) <= radius
}

export default {
  addEventListener,
  registerBackgroundHandler,
  getPendingBackgroundEvents,
  clearPendingBackgroundEvents,
  getPermissionStatus,
  requestForegroundPermission,
  requestBackgroundPermission,
  requestPermission,
  requestTemporaryFullAccuracy,
  shouldShowRationale,
  openAppSettings,
  openLocationSettings,
  isLocationServicesEnabled,
  getProviderStatus,
  checkLocationSettings,
  requestLocationSettingsResolution,
  startServiceSession,
  startBackgroundActivitySession,
  getCurrentPosition,
  getLastKnownPosition,
  watchPosition,
  clearWatch,
  clearAllWatches,
  startBackgroundUpdates,
  stopBackgroundUpdates,
  startSignificantLocationChanges,
  stopSignificantLocationChanges,
  startVisitMonitoring,
  stopVisitMonitoring,
  getBackgroundStatus,
  addGeofence,
  addGeofences,
  removeGeofence,
  removeAllGeofences,
  getMonitoredGeofences,
  requestGeofenceState,
  getMaxMonitoredGeofences,
  startBeaconRanging,
  stopBeaconRanging,
  startBeaconMonitoring,
  stopBeaconMonitoring,
  startHeadingUpdates,
  stopHeadingUpdates,
  getCurrentHeading,
  dismissHeadingCalibrationDisplay,
  startAltitudeUpdates,
  stopAltitudeUpdates,
  getGnssInfo,
  startGnssUpdates,
  stopGnssUpdates,
  isGeocoderAvailable,
  geocode,
  reverseGeocode,
  setMockLocationEnabled,
  setMockLocation,
  isLocationAvailable,
  getCapabilities,
  getDistance,
  getBearing,
  getDestination,
  isPointWithinRadius,
}
