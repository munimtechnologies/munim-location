import React, { useCallback, useEffect, useRef, useState } from 'react'
import {
  Linking,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import * as Location from 'munim-location'
import { clearLog, log, subscribeLog, type LogEntry } from './src/eventLog'
import { runChecks, saveReport, type ChecksReport } from './src/checks'

const LOGGED_EVENTS: Location.LocationEventName[] = [
  'locationError',
  'locationAvailability',
  'locationUpdatesPaused',
  'locationUpdatesResumed',
  'liveUpdateDiagnostic',
  'authorizationChanged',
  'providerChanged',
  'airplaneModeChanged',
  'geofenceState',
  'geofenceError',
  'beaconsRanged',
  'beaconRangingError',
  'headingCalibrationNeeded',
  'altitude',
  'altitudeError',
  'gnssStarted',
  'gnssStopped',
  'gnssFirstFix',
  'gnssStatus',
  'serviceSessionDiagnostic',
  'backgroundActivitySessionDiagnostic',
  'locationSettingsResolved',
]

type Tab =
  | 'Checks'
  | 'Permissions'
  | 'Position'
  | 'Background'
  | 'Regions'
  | 'Heading'
  | 'GNSS'
  | 'Geocode'

const TABS: Tab[] = [
  'Checks',
  'Permissions',
  'Position',
  'Background',
  'Regions',
  'Heading',
  'GNSS',
  'Geocode',
]

async function attempt(name: string, action: () => unknown) {
  try {
    const result = await action()
    log(name, result === undefined ? 'ok' : result)
  } catch (error) {
    const err = Location.toLocationError(error)
    log(`${name} failed`, `${err.code}: ${err.message}`)
  }
}

function Button({ title, onPress }: { title: string; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
    >
      <Text style={styles.buttonText}>{title}</Text>
    </Pressable>
  )
}

function Row({ children }: { children: React.ReactNode }) {
  return <View style={styles.row}>{children}</View>
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  )
}

function ChecksScreen({
  report,
  running,
  onRun,
}: {
  report?: ChecksReport
  running: boolean
  onRun: () => void
}) {
  return (
    <Section title="API checks">
      <Text style={styles.note}>
        Runs every API with the permission the app has now. It never shows a
        permission prompt. Deep link: munimlocationexample://checks
      </Text>
      <Button title={running ? 'Running…' : 'Run checks'} onPress={onRun} />
      {report && (
        <View>
          <Text style={styles.summary}>
            {report.summary.pass} pass · {report.summary.fail} fail ·{' '}
            {report.summary.skip} skip ({report.permission?.status})
          </Text>
          {report.results.map(result => (
            <Text key={result.name} style={styles.result}>
              {result.status === 'pass' ? '✓' : result.status === 'fail' ? '✗' : '–'}{' '}
              {result.name}
              {result.error ? ` — ${result.error}` : ''}
            </Text>
          ))}
        </View>
      )}
    </Section>
  )
}

function PermissionsScreen() {
  return (
    <>
      <Section title="Permissions">
        <Row>
          <Button title="Status" onPress={() => attempt('getPermissionStatus', Location.getPermissionStatus)} />
          <Button
            title="Request foreground"
            onPress={() => attempt('requestForegroundPermission', () => Location.requestForegroundPermission())}
          />
          <Button
            title="Approximate only"
            onPress={() =>
              attempt('requestForegroundPermission(precise: false)', () =>
                Location.requestForegroundPermission({ precise: false }),
              )
            }
          />
          <Button
            title="Request background"
            onPress={() => attempt('requestBackgroundPermission', Location.requestBackgroundPermission)}
          />
          <Button
            title="Temporary full accuracy"
            onPress={() =>
              attempt('requestTemporaryFullAccuracy', () =>
                Location.requestTemporaryFullAccuracy('ExampleNavigation'),
              )
            }
          />
          <Button
            title="Rationale?"
            onPress={() =>
              attempt('shouldShowRationale', () => ({
                fine: Location.shouldShowRationale('fine'),
                coarse: Location.shouldShowRationale('coarse'),
                background: Location.shouldShowRationale('background'),
              }))
            }
          />
          <Button title="App settings" onPress={() => attempt('openAppSettings', Location.openAppSettings)} />
        </Row>
      </Section>
      <Section title="Services">
        <Row>
          <Button title="Services on?" onPress={() => attempt('isLocationServicesEnabled', Location.isLocationServicesEnabled)} />
          <Button title="Providers" onPress={() => attempt('getProviderStatus', Location.getProviderStatus)} />
          <Button title="Check settings" onPress={() => attempt('checkLocationSettings', () => Location.checkLocationSettings())} />
          <Button
            title="Fix settings"
            onPress={() => attempt('requestLocationSettingsResolution', () => Location.requestLocationSettingsResolution())}
          />
          <Button title="Location settings" onPress={() => attempt('openLocationSettings', Location.openLocationSettings)} />
          <Button title="Capabilities" onPress={() => attempt('getCapabilities', Location.getCapabilities)} />
        </Row>
      </Section>
    </>
  )
}

function PositionScreen() {
  const watchId = useRef<number | null>(null)
  const session = useRef<Location.ServiceSession | null>(null)
  const [last, setLast] = useState<Location.Location | null>(null)

  useEffect(
    () => () => {
      if (watchId.current !== null) Location.clearWatch(watchId.current)
      session.current?.stop()
    },
    [],
  )

  const startWatch = (options: Partial<Location.WatchOptions>, label: string) => {
    if (watchId.current !== null) Location.clearWatch(watchId.current)
    watchId.current = Location.watchPosition(
      location => {
        setLast(location)
        log('location', location)
      },
      error => log('watch error', `${error.code}: ${error.message}`),
      options,
    )
    log(`watchPosition (${label})`, { watchId: watchId.current })
  }

  return (
    <>
      <Section title="One-shot">
        <Row>
          <Button title="Current (best)" onPress={() => attempt('getCurrentPosition', () => Location.getCurrentPosition())} />
          <Button
            title="Current (balanced)"
            onPress={() =>
              attempt('getCurrentPosition balanced', () =>
                Location.getCurrentPosition({ accuracy: 'hundredMeters', priority: 'balanced' }),
              )
            }
          />
          <Button title="Last known" onPress={() => attempt('getLastKnownPosition', () => Location.getLastKnownPosition())} />
        </Row>
      </Section>
      <Section title="Watch">
        <Row>
          <Button title="Watch 10 m" onPress={() => startWatch({ distanceFilter: 10 }, '10 m')} />
          <Button
            title="Watch batched"
            onPress={() => startWatch({ intervalMs: 2000, maxUpdateDelayMs: 10000 }, 'batched')}
          />
          <Button title="Live updates" onPress={() => startWatch({ useLiveUpdates: true, activityType: 'fitness' }, 'live')} />
          <Button
            title="Stop watch"
            onPress={() => {
              if (watchId.current !== null) Location.clearWatch(watchId.current)
              watchId.current = null
              log('clearWatch')
            }}
          />
          <Button
            title="Service session"
            onPress={() => {
              session.current?.stop()
              session.current = Location.startServiceSession({ authorization: 'whenInUse' })
              log('startServiceSession', session.current)
            }}
          />
        </Row>
        {last && (
          <Text style={styles.mono}>
            {last.latitude.toFixed(6)}, {last.longitude.toFixed(6)} ±{last.horizontalAccuracy.toFixed(0)} m
            {last.speed !== undefined ? ` · ${last.speed.toFixed(1)} m/s` : ''}
            {last.isMock ? ' · mock' : ''}
          </Text>
        )}
      </Section>
      {Platform.OS === 'android' && (
        <Section title="Mock location (Android dev builds)">
          <Row>
            <Button title="Enable mock" onPress={() => attempt('setMockLocationEnabled', () => Location.setMockLocationEnabled(true))} />
            <Button
              title="Mock Eiffel Tower"
              onPress={() => attempt('setMockLocation', () => Location.setMockLocation({ latitude: 48.8584, longitude: 2.2945 }))}
            />
            <Button title="Disable mock" onPress={() => attempt('setMockLocationEnabled', () => Location.setMockLocationEnabled(false))} />
          </Row>
        </Section>
      )}
    </>
  )
}

function BackgroundScreen() {
  return (
    <Section title="Background and terminated delivery">
      <Text style={styles.note}>
        Background events reach the handler registered in index.js (Android
        Headless JS when the app was killed; iOS replays events persisted
        while the app was relaunched in the background).
      </Text>
      <Row>
        <Button
          title="Start background"
          onPress={() =>
            attempt('startBackgroundUpdates', () =>
              Location.startBackgroundUpdates({
                distanceFilter: 25,
                android: { restartOnBoot: true, notificationTitle: 'munim-location example' },
              }),
            )
          }
        />
        <Button
          title="Background (PendingIntent)"
          onPress={() =>
            attempt('startBackgroundUpdates pendingIntent', () =>
              Location.startBackgroundUpdates({ android: { mode: 'pendingIntent' } }),
            )
          }
        />
        <Button title="Stop background" onPress={() => attempt('stopBackgroundUpdates', Location.stopBackgroundUpdates)} />
        <Button title="Significant changes" onPress={() => attempt('startSignificantLocationChanges', Location.startSignificantLocationChanges)} />
        <Button title="Stop significant" onPress={() => attempt('stopSignificantLocationChanges', Location.stopSignificantLocationChanges)} />
        <Button title="Visits" onPress={() => attempt('startVisitMonitoring', Location.startVisitMonitoring)} />
        <Button title="Stop visits" onPress={() => attempt('stopVisitMonitoring', Location.stopVisitMonitoring)} />
        <Button
          title="Activity session"
          onPress={() => attempt('startBackgroundActivitySession', () => Location.startBackgroundActivitySession())}
        />
        <Button title="Status" onPress={() => attempt('getBackgroundStatus', Location.getBackgroundStatus)} />
        <Button title="Pending events" onPress={() => attempt('getPendingBackgroundEvents', Location.getPendingBackgroundEvents)} />
      </Row>
    </Section>
  )
}

function RegionsScreen() {
  const [radius, setRadius] = useState('150')
  const addHere = async () => {
    const here = await Location.getCurrentPosition({ accuracy: 'hundredMeters' })
    await Location.addGeofence({
      identifier: `fence-${Date.now()}`,
      latitude: here.latitude,
      longitude: here.longitude,
      radius: Number(radius) || 150,
      notifyOnDwell: true,
      loiteringDelayMs: 60000,
      notifyOnStartIfInside: true,
    })
    return Location.getMonitoredGeofences()
  }
  return (
    <>
      <Section title="Geofences">
        <Row>
          <TextInput
            style={styles.input}
            value={radius}
            onChangeText={setRadius}
            keyboardType="numeric"
            placeholder="radius m"
          />
          <Button title="Add here" onPress={() => attempt('addGeofence', addHere)} />
          <Button title="List" onPress={() => attempt('getMonitoredGeofences', Location.getMonitoredGeofences)} />
          <Button
            title="States"
            onPress={() =>
              attempt('requestGeofenceState', async () => {
                const regions = await Location.getMonitoredGeofences()
                return Promise.all(
                  regions.map(async region => ({
                    id: region.identifier,
                    state: await Location.requestGeofenceState(region.identifier),
                  })),
                )
              })
            }
          />
          <Button title="Remove all" onPress={() => attempt('removeAllGeofences', Location.removeAllGeofences)} />
          <Button title="Max" onPress={() => attempt('getMaxMonitoredGeofences', Location.getMaxMonitoredGeofences)} />
        </Row>
      </Section>
      <Section title="iBeacon (iOS)">
        <Row>
          <Button
            title="Range"
            onPress={() =>
              attempt('startBeaconRanging', () =>
                Location.startBeaconRanging({ identifier: 'demo', uuid: 'E2C56DB5-DFFB-48D2-B060-D0F5A71096E0' }),
              )
            }
          />
          <Button title="Stop ranging" onPress={() => attempt('stopBeaconRanging', () => Location.stopBeaconRanging('demo'))} />
          <Button
            title="Monitor"
            onPress={() =>
              attempt('startBeaconMonitoring', () =>
                Location.startBeaconMonitoring({
                  identifier: 'demo-region',
                  uuid: 'E2C56DB5-DFFB-48D2-B060-D0F5A71096E0',
                  notifyEntryStateOnDisplay: true,
                }),
              )
            }
          />
          <Button title="Stop monitor" onPress={() => attempt('stopBeaconMonitoring', () => Location.stopBeaconMonitoring('demo-region'))} />
        </Row>
      </Section>
    </>
  )
}

function HeadingScreen() {
  const [heading, setHeading] = useState<Location.Heading | null>(null)
  useEffect(() => Location.addEventListener('heading', setHeading), [])
  return (
    <>
      <Section title="Compass">
        <Row>
          <Button title="Start" onPress={() => attempt('startHeadingUpdates', () => Location.startHeadingUpdates())} />
          <Button title="Stop" onPress={() => attempt('stopHeadingUpdates', Location.stopHeadingUpdates)} />
          <Button title="Current" onPress={() => attempt('getCurrentHeading', () => Location.getCurrentHeading())} />
          <Button title="Dismiss calibration" onPress={() => attempt('dismiss', Location.dismissHeadingCalibrationDisplay)} />
        </Row>
        {heading && (
          <Text style={styles.big}>
            {heading.magneticHeading.toFixed(0)}° mag · {heading.trueHeading >= 0 ? `${heading.trueHeading.toFixed(0)}° true` : 'true n/a'} ·
            ±{heading.headingAccuracy.toFixed(0)}° ({heading.source})
          </Text>
        )}
      </Section>
      <Section title="Altimeter">
        <Row>
          <Button title="Start" onPress={() => attempt('startAltitudeUpdates', () => Location.startAltitudeUpdates({ absolute: true }))} />
          <Button title="Stop" onPress={() => attempt('stopAltitudeUpdates', Location.stopAltitudeUpdates)} />
        </Row>
      </Section>
    </>
  )
}

function GnssScreen() {
  const [status, setStatus] = useState<Location.LocationEventMap['gnssStatus'] | null>(null)
  useEffect(() => Location.addEventListener('gnssStatus', setStatus), [])
  return (
    <Section title="GNSS (Android)">
      <Row>
        <Button title="Info" onPress={() => attempt('getGnssInfo', Location.getGnssInfo)} />
        <Button title="Status + NMEA" onPress={() => attempt('startGnssUpdates', () => Location.startGnssUpdates({ nmea: true }))} />
        <Button
          title="+ Raw measurements"
          onPress={() =>
            attempt('startGnssUpdates raw', () =>
              Location.startGnssUpdates({ nmea: false, measurements: true, navigationMessages: true }),
            )
          }
        />
        <Button title="Stop" onPress={() => attempt('stopGnssUpdates', Location.stopGnssUpdates)} />
      </Row>
      {status && (
        <View>
          <Text style={styles.mono}>
            {status.usedInFixCount}/{status.satelliteCount} satellites used in fix
          </Text>
          {status.satellites.slice(0, 12).map(satellite => (
            <Text key={`${satellite.constellation}-${satellite.svid}`} style={styles.mono}>
              {satellite.constellation} {satellite.svid} · {satellite.cn0DbHz.toFixed(0)} dB-Hz ·
              el {satellite.elevationDegrees.toFixed(0)}° az {satellite.azimuthDegrees.toFixed(0)}°
              {satellite.usedInFix ? ' · used' : ''}
            </Text>
          ))}
        </View>
      )}
    </Section>
  )
}

function GeocodeScreen() {
  const [query, setQuery] = useState('1 Apple Park Way, Cupertino')
  return (
    <Section title="Geocoding">
      <TextInput style={styles.input} value={query} onChangeText={setQuery} />
      <Row>
        <Button title="Geocode" onPress={() => attempt('geocode', () => Location.geocode(query))} />
        <Button
          title="Reverse (here)"
          onPress={() =>
            attempt('reverseGeocode', async () => {
              const here = await Location.getCurrentPosition({ accuracy: 'hundredMeters' })
              return Location.reverseGeocode(here)
            })
          }
        />
        <Button
          title="Distance to Apple Park"
          onPress={() =>
            attempt('getDistance', async () => {
              const here = await Location.getCurrentPosition({ accuracy: 'hundredMeters' })
              const park = { latitude: 37.3349, longitude: -122.009 }
              return {
                meters: Math.round(Location.getDistance(here, park)),
                bearing: Location.getBearing(here, park).toFixed(1),
              }
            })
          }
        />
      </Row>
    </Section>
  )
}

export default function App() {
  const [tab, setTab] = useState<Tab>('Checks')
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [report, setReport] = useState<ChecksReport>()
  const [running, setRunning] = useState(false)

  useEffect(() => subscribeLog(setEntries), [])
  useEffect(() => {
    const unsubscribers = LOGGED_EVENTS.map(name =>
      Location.addEventListener(name, payload => log(name, payload)),
    )
    return () => unsubscribers.forEach(unsubscribe => unsubscribe())
  }, [])

  const run = useCallback(async () => {
    if (running) return
    setRunning(true)
    setTab('Checks')
    log('checks', 'started')
    try {
      const result = await runChecks(check => log(`check ${check.status}`, check.name))
      setReport(result)
      const path = await saveReport(result)
      log('checks', { ...result.summary, path })
    } catch (error) {
      log('checks crashed', String(error))
    } finally {
      setRunning(false)
    }
  }, [running])

  // munimlocationexample://checks runs the checks (no auto-run on launch).
  useEffect(() => {
    const handle = (url: string | null | undefined) => {
      const route = url?.replace(/\/$/, '').split('://')[1]
      if (route === 'checks') {
        run()
      } else if (route === 'background-start') {
        // Headless probe: PendingIntent delivery keeps arriving after the
        // process is killed (Headless JS or the persisted queue).
        attempt('startBackgroundUpdates (deep link)', () =>
          Location.startBackgroundUpdates({
            intervalMs: 2000,
            fastestIntervalMs: 1000,
            android: { mode: 'pendingIntent' },
          }),
        )
      } else if (route === 'background-start-fgs') {
        attempt('startBackgroundUpdates fgs (deep link)', () =>
          Location.startBackgroundUpdates({
            intervalMs: 2000,
            fastestIntervalMs: 1000,
            android: { mode: 'foregroundService', notificationTitle: 'munim-location probe' },
          }),
        )
      } else if (route === 'background-stop') {
        attempt('stopBackgroundUpdates (deep link)', Location.stopBackgroundUpdates)
      }
    }
    Linking.getInitialURL().then(handle)
    const subscription = Linking.addEventListener('url', event => handle(event.url))
    return () => subscription.remove()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container}>
        <StatusBar barStyle="dark-content" />
        <Text style={styles.title}>munim-location</Text>
        <ScrollView horizontal style={styles.tabs} showsHorizontalScrollIndicator={false}>
          {TABS.map(name => (
            <Pressable
              key={name}
              onPress={() => setTab(name)}
              style={[styles.tab, tab === name && styles.tabActive]}
            >
              <Text style={[styles.tabText, tab === name && styles.tabTextActive]}>{name}</Text>
            </Pressable>
          ))}
        </ScrollView>
        <ScrollView style={styles.content} contentContainerStyle={styles.contentInner}>
          {tab === 'Checks' && <ChecksScreen report={report} running={running} onRun={run} />}
          {tab === 'Permissions' && <PermissionsScreen />}
          {tab === 'Position' && <PositionScreen />}
          {tab === 'Background' && <BackgroundScreen />}
          {tab === 'Regions' && <RegionsScreen />}
          {tab === 'Heading' && <HeadingScreen />}
          {tab === 'GNSS' && <GnssScreen />}
          {tab === 'Geocode' && <GeocodeScreen />}
        </ScrollView>
        <View style={styles.logHeader}>
          <Text style={styles.sectionTitle}>Event log</Text>
          <Pressable onPress={clearLog}>
            <Text style={styles.link}>Clear</Text>
          </Pressable>
        </View>
        <ScrollView style={styles.log}>
          {entries.map(entry => (
            <Text key={entry.id} style={styles.logLine}>
              <Text style={styles.logTime}>{entry.time} </Text>
              <Text style={styles.logName}>{entry.name} </Text>
              {entry.detail}
            </Text>
          ))}
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F4F6FA' },
  title: { fontSize: 22, fontWeight: '700', paddingHorizontal: 16, paddingTop: 8, color: '#0B1F3A' },
  tabs: { flexGrow: 0, paddingHorizontal: 8, paddingVertical: 8 },
  tab: { paddingHorizontal: 12, paddingVertical: 6, borderRadius: 14, marginHorizontal: 4, backgroundColor: '#E2E8F0' },
  tabActive: { backgroundColor: '#0066CC' },
  tabText: { color: '#1E293B', fontWeight: '600' },
  tabTextActive: { color: '#FFFFFF' },
  content: { flex: 1 },
  contentInner: { padding: 12 },
  section: { backgroundColor: '#FFFFFF', borderRadius: 10, padding: 12, marginBottom: 12 },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#0B1F3A', marginBottom: 8 },
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center' },
  button: { backgroundColor: '#0066CC', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 8, margin: 4 },
  buttonPressed: { opacity: 0.7 },
  buttonText: { color: '#FFFFFF', fontWeight: '600' },
  note: { color: '#475569', marginBottom: 8 },
  summary: { fontWeight: '700', marginVertical: 8 },
  result: { fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }), fontSize: 12, marginVertical: 1 },
  mono: { fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }), fontSize: 12, marginTop: 6 },
  big: { fontSize: 18, fontWeight: '600', marginTop: 8 },
  input: { borderWidth: 1, borderColor: '#CBD5E1', borderRadius: 8, paddingHorizontal: 8, paddingVertical: 6, minWidth: 90, margin: 4, backgroundColor: '#FFFFFF' },
  logHeader: { flexDirection: 'row', justifyContent: 'space-between', paddingHorizontal: 16, paddingTop: 6 },
  link: { color: '#0066CC', fontWeight: '600' },
  log: { maxHeight: 220, paddingHorizontal: 16, backgroundColor: '#0B1F3A' },
  logLine: { color: '#E2E8F0', fontSize: 11, fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }), marginVertical: 1 },
  logTime: { color: '#94A3B8' },
  logName: { color: '#7DD3FC', fontWeight: '700' },
})
