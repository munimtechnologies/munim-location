/**
 * "Run checks": exercises the munim-location API surface with whatever
 * permission the app currently holds. It never shows a permission prompt
 * (nobody may be there to answer it); checks that need a grant are
 * recorded as `skip` with the reason. Triggered by the Run checks button or
 * the `munimlocationexample://checks` deep link.
 */
import { NativeModules, Platform } from 'react-native'
import * as Location from 'munim-location'

export type CheckStatus = 'pass' | 'fail' | 'skip'

export interface CheckResult {
  name: string
  status: CheckStatus
  ms: number
  detail?: unknown
  error?: string
}

export interface ChecksReport {
  platform: string
  osVersion: string | number
  startedAt: string
  finishedAt: string
  permission?: Location.PermissionStatus
  summary: { pass: number; fail: number; skip: number }
  results: CheckResult[]
}

class Skip extends Error {}

const sleep = (ms: number) =>
  new Promise<void>(resolve => setTimeout(() => resolve(), ms))

function withTimeout<T>(promise: Promise<T>, ms: number, what: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error(`${what} did not settle within ${ms} ms`)),
      ms
    )
    promise.then(
      value => {
        clearTimeout(timer)
        resolve(value)
      },
      error => {
        clearTimeout(timer)
        reject(error)
      }
    )
  })
}

/** Collects events of one name for `ms` milliseconds. */
async function collect<Name extends Location.LocationEventName>(
  name: Name,
  ms: number,
  start: () => void | Promise<unknown>,
  stop: () => void
): Promise<Location.LocationEventMap[Name][]> {
  const events: Location.LocationEventMap[Name][] = []
  const unsubscribe = Location.addEventListener(name, payload => {
    events.push(payload)
  })
  try {
    await start()
    await sleep(ms)
  } finally {
    stop()
    unsubscribe()
  }
  return events
}

export async function runChecks(
  onProgress?: (result: CheckResult) => void
): Promise<ChecksReport> {
  const results: CheckResult[] = []
  const startedAt = new Date().toISOString()
  const ios = Platform.OS === 'ios'
  const android = Platform.OS === 'android'

  async function check(name: string, body: () => Promise<unknown>) {
    const started = Date.now()
    let result: CheckResult
    try {
      const detail = await withTimeout(body(), 45000, name)
      result = { name, status: 'pass', ms: Date.now() - started, detail }
    } catch (error) {
      if (error instanceof Skip) {
        result = { name, status: 'skip', ms: Date.now() - started, error: error.message }
      } else {
        const code = error instanceof Location.LocationError ? `${error.code}: ` : ''
        result = {
          name,
          status: 'fail',
          ms: Date.now() - started,
          error: `${code}${error instanceof Error ? error.message : String(error)}`,
        }
      }
    }
    results.push(result)
    onProgress?.(result)
  }

  const expect = (condition: boolean, message: string) => {
    if (!condition) throw new Error(message)
  }

  let permission: Location.PermissionStatus | undefined
  let capabilities: Location.LocationCapabilities | undefined

  // ---------- Capabilities, permissions, services ----------
  await check('getCapabilities', async () => {
    capabilities = await Location.getCapabilities()
    expect(capabilities.platform === Platform.OS, 'platform mismatch')
    return capabilities
  })
  await check('getPermissionStatus', async () => {
    permission = await Location.getPermissionStatus()
    return permission
  })
  const granted = permission?.foreground ?? false
  const needsGrant = () => {
    if (!granted) throw new Skip(`needs location permission (status ${permission?.status})`)
  }

  await check('permission status shape (expo-location parity)', async () => {
    const status = await Location.getPermissionStatus()
    expect(typeof status.canAskAgain === 'boolean', 'canAskAgain missing')
    expect(typeof status.foreground === 'boolean', 'foreground missing')
    expect(typeof status.background === 'boolean', 'background missing')
    return {
      status: status.status,
      foreground: status.foreground,
      background: status.background,
      canAskAgain: status.canAskAgain,
    }
  })
  await check('isLocationServicesEnabled', () => Location.isLocationServicesEnabled())
  await check('getProviderStatus', () => Location.getProviderStatus())
  await check('checkLocationSettings', () => Location.checkLocationSettings())
  await check('shouldShowRationale', async () => ({
    fine: Location.shouldShowRationale('fine'),
    background: Location.shouldShowRationale('background'),
  }))
  await check('isLocationAvailable', () => Location.isLocationAvailable())
  await check('getBackgroundStatus', async () => Location.getBackgroundStatus())
  await check('getPendingBackgroundEvents', async () => {
    const pending = Location.getPendingBackgroundEvents()
    expect(Array.isArray(pending), 'pending events are not an array')
    return { count: pending.length }
  })
  await check('requestTemporaryFullAccuracy (no prompt when already full)', async () => {
    if (ios && permission?.accuracy !== 'full') {
      throw new Skip('accuracy is reduced; the request would show a prompt')
    }
    return Location.requestTemporaryFullAccuracy('ExampleNavigation')
  })

  // ---------- Sessions (iOS 17/18) ----------
  await check('startServiceSession / stop', async () => {
    const session = Location.startServiceSession({ authorization: 'whenInUse' })
    await sleep(500)
    session.stop()
    if (ios && capabilities?.serviceSessionAvailable) {
      expect(session.supported, 'iOS 18 session should be supported')
    }
    return { supported: session.supported, id: session.id }
  })
  await check('startBackgroundActivitySession / stop', async () => {
    const session = Location.startBackgroundActivitySession()
    await sleep(300)
    session.stop()
    return { supported: session.supported }
  })

  // ---------- Positions ----------
  await check('getLastKnownPosition', async () => {
    const last = await Location.getLastKnownPosition()
    return last ?? 'none cached'
  })
  await check('getCurrentPosition', async () => {
    if (!granted) {
      try {
        await Location.getCurrentPosition({ timeoutMs: 5000 })
      } catch (error) {
        const code = (error as Location.LocationError).code
        expect(
          code === 'E_LOCATION_PERMISSION_DENIED' || code === 'E_LOCATION_SERVICES_DISABLED',
          `expected a permission error, got ${code}`
        )
        throw new Skip(`rejected as expected without permission (${code})`)
      }
      throw new Error('resolved without permission')
    }
    const location = await Location.getCurrentPosition({
      accuracy: 'hundredMeters',
      timeoutMs: 25000,
    })
    expect(Math.abs(location.latitude) <= 90, 'latitude out of range')
    return location
  })
  await check('getCurrentPosition (maximumAge cache)', async () => {
    needsGrant()
    const started = Date.now()
    const location = await Location.getCurrentPosition({
      accuracy: 'kilometer',
      maximumAgeMs: 10 * 60 * 1000,
      timeoutMs: 15000,
    })
    return { ms: Date.now() - started, horizontalAccuracy: location.horizontalAccuracy }
  })
  await check('watchPosition (6 s)', async () => {
    needsGrant()
    const locations: Location.Location[] = []
    const errors: string[] = []
    const id = Location.watchPosition(
      location => locations.push(location),
      error => errors.push(error.code),
      { accuracy: 'nearestTenMeters', intervalMs: 1000 }
    )
    await sleep(6000)
    Location.clearWatch(id)
    expect(locations.length > 0, `no locations (errors: ${errors.join(', ') || 'none'})`)
    return { count: locations.length, last: locations[locations.length - 1], errors }
  })
  await check('watchPosition liveUpdates (iOS 17+)', async () => {
    if (!ios || !capabilities?.liveUpdatesAvailable) throw new Skip('iOS 17+ only')
    needsGrant()
    const locations: Location.Location[] = []
    const diagnostics: unknown[] = []
    const unsubscribe = Location.addEventListener('liveUpdateDiagnostic', payload =>
      diagnostics.push(payload)
    )
    const id = Location.watchPosition(location => locations.push(location), undefined, {
      useLiveUpdates: true,
    })
    await sleep(6000)
    Location.clearWatch(id)
    unsubscribe()
    expect(locations.length > 0, 'no live updates')
    return { count: locations.length, diagnostics }
  })

  await check('getLastKnownPosition (maximumAgeMs)', async () => {
    needsGrant()
    const any = await Location.getLastKnownPosition()
    const recent = await Location.getLastKnownPosition({ maximumAgeMs: 2 * 60 * 1000 })
    const tooOld = await Location.getLastKnownPosition({ maximumAgeMs: 1 })
    if (recent) {
      expect(Date.now() - recent.timestamp < 2 * 60 * 1000 + 5000, 'recent fix is too old')
    }
    expect(tooOld === undefined || Date.now() - tooOld.timestamp < 1000, '1 ms maxAge returned an old fix')
    return { any: !!any, recent: !!recent, tooOld: !!tooOld }
  })
  await check('Accuracy presets (Low / Balanced / High)', async () => {
    expect(Location.Accuracy.Low === 'kilometer', 'Low')
    expect(Location.Accuracy.Balanced === 'hundredMeters', 'Balanced')
    expect(Location.Accuracy.High === 'nearestTenMeters', 'High')
    needsGrant()
    const fixes: Record<string, number> = {}
    for (const preset of ['Low', 'Balanced', 'High'] as const) {
      const location = await Location.getCurrentPosition({
        accuracy: Location.Accuracy[preset],
        timeoutMs: 15000,
      })
      fixes[preset] = Math.round(location.horizontalAccuracy)
    }
    return fixes
  })
  await check('subscribeToPosition / remove (5 s)', async () => {
    needsGrant()
    const locations: Location.Location[] = []
    const subscription = Location.subscribeToPosition(
      location => locations.push(location),
      { accuracy: Location.Accuracy.Balanced, intervalMs: 1000, distanceFilter: 3 }
    )
    await sleep(5000)
    subscription.remove()
    subscription.remove()
    const count = locations.length
    await sleep(2500)
    expect(count > 0, 'no locations')
    expect(locations.length === count, `updates after remove (${locations.length - count})`)
    return { count, watchId: subscription.watchId }
  })

  // ---------- Background ----------
  await check('startSignificantLocationChanges / stop', async () => {
    needsGrant()
    const started = await Location.startSignificantLocationChanges()
    const status = Location.getBackgroundStatus()
    Location.stopSignificantLocationChanges()
    return { started, status }
  })
  await check('startVisitMonitoring / stop', async () => {
    if (!ios) {
      try {
        await Location.startVisitMonitoring()
      } catch (error) {
        expect((error as Location.LocationError).code === 'E_UNSUPPORTED', 'expected E_UNSUPPORTED')
        return 'unsupported on Android (expected)'
      }
      throw new Error('Android visit monitoring should be unsupported')
    }
    needsGrant()
    const started = await Location.startVisitMonitoring()
    Location.stopVisitMonitoring()
    return { started }
  })
  await check('startBackgroundUpdates / stop (4 s)', async () => {
    needsGrant()
    const events = await collect(
      'backgroundLocation',
      4000,
      () =>
        Location.startBackgroundUpdates({
          accuracy: 'hundredMeters',
          intervalMs: 1000,
          android: { notificationTitle: 'munim-location checks' },
        }),
      () => {
        Location.stopBackgroundUpdates().catch(() => {})
      }
    )
    await sleep(300)
    return {
      events: events.length,
      status: Location.getBackgroundStatus(),
    }
  })

  // ---------- Geofencing ----------
  await check('geofence add / list / state / remove', async () => {
    if (!capabilities?.regionMonitoringAvailable) throw new Skip('region monitoring unavailable')
    if (android) needsGrant()
    const center = (await Location.getLastKnownPosition()) ?? {
      latitude: 37.3349,
      longitude: -122.009,
    }
    const identifier = 'munim-location-check'
    await Location.addGeofence({
      identifier,
      latitude: center.latitude,
      longitude: center.longitude,
      radius: 200,
      notifyOnDwell: true,
      loiteringDelayMs: 60000,
    })
    const listed = await Location.getMonitoredGeofences()
    expect(listed.some(region => region.identifier === identifier), 'geofence not listed')
    const state = await withTimeout(
      Location.requestGeofenceState(identifier),
      12000,
      'requestGeofenceState'
    ).catch(error => `error: ${String(error)}`)
    await Location.removeGeofence(identifier)
    const after = await Location.getMonitoredGeofences()
    expect(!after.some(region => region.identifier === identifier), 'geofence not removed')
    return {
      engine: capabilities?.geofencingEngine,
      max: Location.getMaxMonitoredGeofences(),
      state,
      listed: listed.length,
      after: after.length,
    }
  })

  // ---------- Beacons ----------
  await check('beacon ranging (3 s)', async () => {
    if (!ios) throw new Skip('iOS only')
    if (!capabilities?.beaconRangingAvailable) throw new Skip('ranging unavailable')
    needsGrant()
    const errors: unknown[] = []
    const unsubscribe = Location.addEventListener('beaconRangingError', payload =>
      errors.push(payload)
    )
    const events = await collect(
      'beaconsRanged',
      3000,
      () =>
        Location.startBeaconRanging({
          identifier: 'check-beacon',
          uuid: 'E2C56DB5-DFFB-48D2-B060-D0F5A71096E0',
        }),
      () => Location.stopBeaconRanging('check-beacon')
    )
    unsubscribe()
    expect(errors.length === 0, `ranging errors: ${JSON.stringify(errors)}`)
    return { rangingCallbacks: events.length }
  })

  // ---------- Heading and altitude ----------
  await check('getCurrentHeading', async () => {
    if (!capabilities?.headingAvailable) throw new Skip('no compass')
    return Location.getCurrentHeading(8000)
  })
  await check('heading events (3 s)', async () => {
    if (!capabilities?.headingAvailable) throw new Skip('no compass')
    const events = await collect(
      'heading',
      3000,
      () => Location.startHeadingUpdates({ headingFilter: 0 }),
      () => Location.stopHeadingUpdates()
    )
    expect(events.length > 0, 'no heading events')
    return { count: events.length, last: events[events.length - 1] }
  })
  await check('subscribeToHeading / remove (3 s)', async () => {
    if (!capabilities?.headingAvailable) throw new Skip('no compass')
    const headings: Location.Heading[] = []
    const subscription = Location.subscribeToHeading(heading => headings.push(heading), {
      headingFilter: 0,
    })
    await sleep(3000)
    subscription.remove()
    const count = headings.length
    await sleep(1000)
    expect(count > 0, 'no headings')
    expect(headings.length === count, 'headings after remove')
    const last = headings[headings.length - 1]!
    return {
      count,
      // expo-location's watchHeadingAsync pattern: trueHeading else magHeading.
      heading: last.trueHeading >= 0 ? last.trueHeading : last.magneticHeading,
    }
  })
  await check('altitude events (3 s)', async () => {
    if (!capabilities?.altimeterAvailable) throw new Skip('no barometer')
    const errors: unknown[] = []
    const unsubscribe = Location.addEventListener('altitudeError', payload =>
      errors.push(payload)
    )
    const events = await collect(
      'altitude',
      3000,
      () => Location.startAltitudeUpdates({ absolute: true }),
      () => Location.stopAltitudeUpdates()
    )
    unsubscribe()
    const motion = errors.find(error =>
      String((error as { code?: string }).code).startsWith('E_MOTION_PERMISSION'),
    ) as { code: string } | undefined
    if (events.length === 0 && motion) {
      throw new Skip(`needs Motion & Fitness permission (${motion.code})`)
    }
    expect(events.length > 0, `no altitude events (errors: ${JSON.stringify(errors)})`)
    return { count: events.length, kinds: [...new Set(events.map(event => event.kind))] }
  })

  // ---------- GNSS ----------
  await check('getGnssInfo', () => Location.getGnssInfo())
  await check('GNSS status + NMEA (6 s)', async () => {
    if (!android) {
      try {
        await Location.startGnssUpdates()
      } catch (error) {
        expect((error as Location.LocationError).code === 'E_UNSUPPORTED', 'expected E_UNSUPPORTED')
        return 'unsupported on iOS (expected)'
      }
      throw new Error('iOS GNSS should be unsupported')
    }
    needsGrant()
    const nmea: unknown[] = []
    const unsubscribe = Location.addEventListener('nmea', payload => nmea.push(payload))
    const status = await collect(
      'gnssStatus',
      6000,
      () => Location.startGnssUpdates({ status: true, nmea: true }),
      () => Location.stopGnssUpdates()
    )
    unsubscribe()
    const last = status[status.length - 1]
    return {
      statusEvents: status.length,
      nmeaMessages: nmea.length,
      satellites: last?.satelliteCount,
      usedInFix: last?.usedInFixCount,
    }
  })

  // ---------- Geocoding ----------
  await check('geocode', async () => {
    if (!Location.isGeocoderAvailable()) throw new Skip('no geocoder')
    const found = await Location.geocode('1 Apple Park Way, Cupertino, CA', { maxResults: 2 })
    expect(found.length > 0, 'no geocode results')
    return found[0]
  })
  await check('reverseGeocode', async () => {
    if (!Location.isGeocoderAvailable()) throw new Skip('no geocoder')
    const found = await Location.reverseGeocode(
      { latitude: 37.3349, longitude: -122.009 },
      { locale: 'en-US' }
    )
    expect(found.length > 0, 'no reverse geocode results')
    return found[0]
  })
  await check('geocode (CLGeocoder provider)', async () => {
    if (!ios) throw new Skip('iOS only')
    const found = await Location.geocode('Eiffel Tower, Paris', {
      provider: 'clgeocoder',
      maxResults: 1,
    })
    expect(found.length > 0, 'no results')
    return found[0]
  })

  // ---------- Mock locations ----------
  await check('mock location', async () => {
    if (!android) {
      try {
        await Location.setMockLocation({ latitude: 0, longitude: 0 })
      } catch (error) {
        expect((error as Location.LocationError).code === 'E_UNSUPPORTED', 'expected E_UNSUPPORTED')
        return 'unsupported on iOS (expected)'
      }
      throw new Error('iOS mock locations should be unsupported')
    }
    needsGrant()
    // Stay next to the real fix: a far-away mock moves the device's
    // geolocation time zone (Android's time zone detector follows it).
    const real = await Location.getCurrentPosition({ accuracy: 'hundredMeters', maximumAgeMs: 60000 })
    const target = Location.getDestination(real, 45, 150)
    const enabled = await Location.setMockLocationEnabled(true).catch(error => {
      throw new Skip(`not the mock location app: ${String(error)}`)
    })
    try {
      const seen: Location.Location[] = []
      const id = Location.watchPosition(location => seen.push(location), undefined, {
        intervalMs: 500,
      })
      for (let i = 0; i < 6; i++) {
        await Location.setMockLocation({ ...target, accuracy: 3 })
        await sleep(500)
      }
      Location.clearWatch(id)
      const mocked = seen.find(location => location.isMock)
      expect(!!mocked, `no mocked fix among ${seen.length} updates`)
      return { enabled, mocked, offsetMeters: Location.getDistance(real, mocked!) }
    } finally {
      await Location.setMockLocationEnabled(false).catch(() => {})
    }
  })

  // ---------- Location push ----------
  await check('startMonitoringLocationPushes / stop', async () => {
    if (!ios) {
      try {
        await Location.startMonitoringLocationPushes()
      } catch (error) {
        expect((error as Location.LocationError).code === 'E_UNSUPPORTED', 'expected E_UNSUPPORTED')
        Location.stopMonitoringLocationPushes()
        return 'unsupported on Android (expected)'
      }
      throw new Error('Android location push should be unsupported')
    }
    expect(capabilities?.locationPushAvailable === true, 'locationPushAvailable is false')
    let token: string
    try {
      token = await Location.startMonitoringLocationPushes()
    } catch (error) {
      const err = error as Location.LocationError
      expect(err.code === 'E_LOCATION_PUSH', `expected E_LOCATION_PUSH, got ${err.code}`)
      throw new Skip(`iOS refused (provisioning without the location push entitlement?): ${err.message}`)
    } finally {
      Location.stopMonitoringLocationPushes()
    }
    expect(/^[0-9a-f]{64,}$/.test(token), `token is not hex: ${token}`)
    return { tokenLength: token.length, tokenPrefix: token.slice(0, 8) }
  })

  // ---------- Utilities ----------
  await check('getDistance / getBearing / getDestination', async () => {
    // Vincenty's published test line: Flinders Peak -> Buninyong.
    const flinders = { latitude: -37.95103341666667, longitude: 144.42486788888888 }
    const buninyong = { latitude: -37.65282113888889, longitude: 143.92649552777777 }
    const distance = Location.getDistance(flinders, buninyong)
    expect(Math.abs(distance - 54972.271) < 0.01, `distance ${distance}`)
    const bearing = Location.getBearing(
      { latitude: 0, longitude: 0 },
      { latitude: 0, longitude: 1 }
    )
    expect(Math.abs(bearing - 90) < 1e-9, `bearing ${bearing}`)
    const destination = Location.getDestination({ latitude: 0, longitude: 0 }, 90, 111195)
    expect(Math.abs(destination.longitude - 1) < 0.001, `destination ${destination.longitude}`)
    return { distance, bearing, destination }
  })

  const summary = { pass: 0, fail: 0, skip: 0 }
  results.forEach(result => {
    summary[result.status] += 1
  })
  return {
    platform: Platform.OS,
    osVersion: Platform.Version,
    startedAt,
    finishedAt: new Date().toISOString(),
    permission,
    summary,
    results,
  }
}

/** Persists the report: Documents/munim-location-checks.json on iOS, one logcat line per check on Android. */
export async function saveReport(report: ChecksReport): Promise<string | undefined> {
  const json = JSON.stringify(report, null, 2)
  const writer = NativeModules.ChecksReport as
    | { write(json: string): Promise<string> }
    | undefined
  report.results.forEach(result => {
    const note = result.error ?? JSON.stringify(result.detail ?? '').slice(0, 300)
    console.log(`MUNIM_LOCATION_CHECK ${result.status} ${result.name} ${note}`)
  })
  console.log(`MUNIM_LOCATION_CHECKS ${JSON.stringify(report.summary)}`)
  if (!writer) return undefined
  return writer.write(json)
}
