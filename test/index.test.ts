/**
 * JavaScript-side behaviour of munim-location with the Nitro object mocked:
 * typed location batches, the JSON fallback channel, option defaults,
 * subscription bookkeeping, error parsing, and geodesy.
 */
import type {
  Location as LocationStruct,
  LocationBatchSource,
  MotionActivity,
} from '../src/specs/munim-location.nitro'

type LocationListener = (source: LocationBatchSource, watchId: number, locations: LocationStruct[]) => void
type EventListener = (name: string, payload: string) => void

const mockNative = {
  locationListener: undefined as LocationListener | undefined,
  eventListener: undefined as EventListener | undefined,
  nextWatchId: 1,
  setLocationListener: jest.fn((listener: LocationListener) => {
    mockNative.locationListener = listener
  }),
  setEventListener: jest.fn((listener: EventListener) => {
    mockNative.eventListener = listener
  }),
  watchPosition: jest.fn(() => mockNative.nextWatchId++),
  clearWatch: jest.fn(),
  clearAllWatches: jest.fn(),
  flushLocations: jest.fn(() => Promise.resolve()),
  startMotionActivityUpdates: jest.fn(() => Promise.resolve()),
  stopMotionActivityUpdates: jest.fn(),
  getLastMotionActivity: jest.fn(() => Promise.resolve(undefined)),
  startGnssUpdates: jest.fn(() => Promise.resolve()),
  startBackgroundUpdates: jest.fn(() => Promise.resolve(true)),
  getPendingBackgroundEvents: jest.fn(() => '[]'),
  clearPendingBackgroundEvents: jest.fn(),
  getCurrentPosition: jest.fn(() =>
    Promise.reject(new Error('E_LOCATION_TIMEOUT: Timed out waiting for a location\n    at com.munimlocation.LocationEngine'))
  ),
}

jest.mock('react-native-nitro-modules', () => ({
  NitroModules: { createHybridObject: () => mockNative },
}))
jest.mock('react-native', () => ({
  AppRegistry: { registerHeadlessTask: jest.fn() },
  Platform: { OS: 'ios' },
}))

// eslint-disable-next-line @typescript-eslint/no-require-imports
const Location = require('../src/index') as typeof import('../src/index')

function fix(latitude: number, timestamp = 1_700_000_000_000): LocationStruct {
  return { latitude, longitude: 2, horizontalAccuracy: 5, timestamp, isMock: false }
}

beforeEach(() => {
  jest.clearAllMocks()
})

describe('typed location channel', () => {
  it('attaches the typed and JSON listeners on import', () => {
    expect(mockNative.locationListener).toBeDefined()
    expect(mockNative.eventListener).toBeDefined()
  })

  it('runs watchPosition callbacks once per fix of a batch', () => {
    const seen: number[] = []
    const id = Location.watchPosition((location) => seen.push(location.latitude))
    mockNative.locationListener!('watch', id, [fix(1), fix(2), fix(3)])
    expect(seen).toEqual([1, 2, 3])
    Location.clearWatch(id)
    mockNative.locationListener!('watch', id, [fix(4)])
    expect(seen).toEqual([1, 2, 3])
  })

  it('hands a whole batch to subscribeToPositionBatches', () => {
    const batches: number[][] = []
    const subscription = Location.subscribeToPositionBatches((locations) =>
      batches.push(locations.map((location) => location.latitude))
    )
    mockNative.locationListener!('watch', subscription.watchId, [fix(1), fix(2)])
    mockNative.locationListener!('watch', subscription.watchId, [fix(3)])
    expect(batches).toEqual([[1, 2], [3]])
    subscription.remove()
    subscription.remove()
    expect(mockNative.clearWatch).toHaveBeenCalledTimes(1)
  })

  it('still publishes the `location` event with the batch', () => {
    const events: unknown[] = []
    const unsubscribe = Location.addEventListener('location', (event) => events.push(event))
    mockNative.locationListener!('watch', 42, [fix(7)])
    unsubscribe()
    expect(events).toEqual([{ watchId: 42, locations: [fix(7)] }])
  })

  it('routes background batches to the background handler', async () => {
    const handled: Array<{ name: string; count: number }> = []
    const unregister = Location.registerBackgroundHandler((event) => {
      if (event.name === 'backgroundLocation' || event.name === 'significantLocationChange') {
        handled.push({ name: event.name, count: event.payload.locations.length })
      }
    })
    mockNative.locationListener!('background', 0, [fix(1), fix(2)])
    mockNative.locationListener!('significantChange', 0, [fix(3)])
    await Promise.resolve()
    unregister()
    expect(handled).toEqual([
      { name: 'backgroundLocation', count: 2 },
      { name: 'significantLocationChange', count: 1 },
    ])
  })

  it('keeps the JSON channel working for location events', () => {
    const seen: number[] = []
    const id = Location.watchPosition((location) => seen.push(location.latitude))
    mockNative.eventListener!('location', JSON.stringify({ watchId: id, locations: [fix(9)] }))
    expect(seen).toEqual([9])
    Location.clearWatch(id)
  })

  it('reports watch errors to onError with a typed code', () => {
    const errors: string[] = []
    const id = Location.watchPosition(
      () => {},
      (error) => errors.push(error.code)
    )
    mockNative.eventListener!(
      'locationError',
      JSON.stringify({ watchId: id, code: 'E_LOCATION_PERMISSION_DENIED', message: 'no' })
    )
    expect(errors).toEqual(['E_LOCATION_PERMISSION_DENIED'])
    Location.clearWatch(id)
  })

  it('isolates a throwing callback from the next fix', () => {
    const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {})
    const id = Location.watchPosition(() => {
      throw new Error('boom')
    })
    expect(() => mockNative.locationListener!('watch', id, [fix(1)])).not.toThrow()
    expect(consoleError).toHaveBeenCalled()
    consoleError.mockRestore()
    Location.clearWatch(id)
  })
})

describe('option defaults', () => {
  it('fills every WatchOptions field, batching off by default', () => {
    Location.watchPosition(() => {})
    const options = (mockNative.watchPosition.mock.calls[0] as unknown[])[0] as Record<string, unknown>
    expect(options).toMatchObject({
      batchSize: 0,
      batchIntervalMs: 0,
      pauseWhenStationary: false,
      intervalMs: 5000,
    })
    expect(Object.values(options).every((value) => value !== undefined)).toBe(true)
  })

  it('batches 10 fixes / 30 s for subscribeToPositionBatches unless overridden', () => {
    Location.subscribeToPositionBatches(() => {}, { batchSize: 4 })
    const options = (mockNative.watchPosition.mock.calls[0] as unknown[])[0] as Record<string, unknown>
    expect(options).toMatchObject({ batchSize: 4, batchIntervalMs: 30000 })
  })

  it('passes batching and pauseWhenStationary to background updates', async () => {
    await Location.startBackgroundUpdates({ batchSize: 5, pauseWhenStationary: true })
    const [watch, android] = mockNative.startBackgroundUpdates.mock.calls[0] as unknown as [
      Record<string, unknown>,
      Record<string, unknown>,
    ]
    expect(watch).toMatchObject({
      batchSize: 5,
      batchIntervalMs: 0,
      pauseWhenStationary: true,
      allowsBackgroundLocationUpdates: true,
    })
    expect(android).toMatchObject({ mode: 'foregroundService' })
  })

  it('sends antennaInfo: false unless asked', async () => {
    await Location.startGnssUpdates()
    expect(mockNative.startGnssUpdates).toHaveBeenCalledWith({
      status: true,
      nmea: false,
      measurements: false,
      navigationMessages: false,
      antennaInfo: false,
    })
  })
})

describe('motion activity', () => {
  it('delivers motionActivity events and stops with the last subscription', async () => {
    const seen: MotionActivity[] = []
    const first = Location.subscribeToMotionActivity((activity) => seen.push(activity))
    const second = Location.subscribeToMotionActivity(() => {})
    await Promise.resolve()
    expect(mockNative.startMotionActivityUpdates).toHaveBeenCalledTimes(2)
    const activity: MotionActivity = { type: 'walking', confidence: 'high', timestamp: 1 }
    mockNative.eventListener!('motionActivity', JSON.stringify(activity))
    expect(seen).toEqual([activity])
    first.remove()
    expect(mockNative.stopMotionActivityUpdates).not.toHaveBeenCalled()
    second.remove()
    second.remove()
    expect(mockNative.stopMotionActivityUpdates).toHaveBeenCalledTimes(1)
  })

  it('reports a failed start to onError', async () => {
    mockNative.startMotionActivityUpdates.mockImplementationOnce(() =>
      Promise.reject(new Error('E_MOTION_PERMISSION_DENIED: off'))
    )
    const errors: string[] = []
    const subscription = Location.subscribeToMotionActivity(
      () => {},
      (error) => errors.push(error.code)
    )
    await new Promise((resolve) => setTimeout(resolve, 0))
    subscription.remove()
    expect(errors).toEqual(['E_MOTION_PERMISSION_DENIED'])
  })
})

describe('errors and utilities', () => {
  it('parses native error codes and strips Java stack traces', async () => {
    await expect(Location.getCurrentPosition()).rejects.toMatchObject({
      name: 'LocationError',
      code: 'E_LOCATION_TIMEOUT',
      message: 'Timed out waiting for a location',
    })
  })

  it('flushLocations resolves through the native flush', async () => {
    await Location.flushLocations()
    expect(mockNative.flushLocations).toHaveBeenCalledTimes(1)
  })

  it('computes WGS84 distances like Android Location.distanceBetween', () => {
    // Eiffel Tower to Notre-Dame: ~4.2 km.
    const distance = Location.getDistance(
      { latitude: 48.8584, longitude: 2.2945 },
      { latitude: 48.853, longitude: 2.3499 }
    )
    expect(distance).toBeGreaterThan(4050)
    expect(distance).toBeLessThan(4150)
    expect(Location.getDistance({ latitude: 1, longitude: 1 }, { latitude: 1, longitude: 1 })).toBe(0)
  })
})
