/**
 * Micro-benchmarks for the JavaScript side of location delivery and for the
 * pending-event queue algorithm (`npm run bench:delivery`).
 *
 * 1. Delivery: 0.3.x sent every fix as its own JSON event that JavaScript
 *    parsed; 0.4 hands typed structs over once per native batch. This
 *    measures the JS-thread work per fix for both shapes (Node/V8, so treat
 *    the numbers as relative). The 0.4 rows rebuild every location object
 *    field by field, standing in for Nitro's struct-to-JS conversion; the
 *    native JSON encoding that 0.4 also drops is not included.
 * 2. Pending queue: 0.3.x re-read, re-parsed, and re-wrote the whole queue
 *    file (up to 500 events) for every event persisted while JavaScript was
 *    not running; 0.4 appends one JSON line and trims rarely. Same algorithm
 *    as the Kotlin and Swift code, run with Node's fs.
 */
import { appendFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const FIXES = 20_000
const BATCH = 10

function fix(index) {
  return {
    latitude: 48.8584 + index * 1e-5,
    longitude: 2.2945 + index * 1e-5,
    altitude: 35.2,
    horizontalAccuracy: 4.7,
    verticalAccuracy: 3.1,
    speed: 1.4,
    speedAccuracy: 0.3,
    course: 87.5,
    courseAccuracy: 9.8,
    timestamp: 1_763_280_000_000 + index * 1000,
    elapsedRealtimeNanos: 123_456_789_000 + index,
    provider: 'fused',
    isMock: false,
    satelliteCount: 14,
  }
}

function time(label, body) {
  for (let warmup = 0; warmup < 3; warmup++) body()
  const runs = []
  for (let run = 0; run < 7; run++) {
    const started = process.hrtime.bigint()
    body()
    runs.push(Number(process.hrtime.bigint() - started) / 1e6)
  }
  runs.sort((a, b) => a - b)
  const median = runs[3]
  return { label, medianMs: median }
}

// ---------- 1. Delivery ----------
const fixes = Array.from({ length: FIXES }, (_, index) => fix(index))
const jsonEvents = fixes.map((location) => JSON.stringify({ watchId: 1, locations: [location] }))
const batches = []
for (let index = 0; index < fixes.length; index += BATCH) batches.push(fixes.slice(index, index + BATCH))

let sink = 0
const onLocation = (location) => {
  sink += location.latitude
}

// Stand-in for Nitro converting a C++ Location struct into a JS object.
const fromStruct = (struct) => ({
  latitude: struct.latitude,
  longitude: struct.longitude,
  altitude: struct.altitude,
  horizontalAccuracy: struct.horizontalAccuracy,
  verticalAccuracy: struct.verticalAccuracy,
  speed: struct.speed,
  speedAccuracy: struct.speedAccuracy,
  course: struct.course,
  courseAccuracy: struct.courseAccuracy,
  timestamp: struct.timestamp,
  elapsedRealtimeNanos: struct.elapsedRealtimeNanos,
  provider: struct.provider,
  isMock: struct.isMock,
  satelliteCount: struct.satelliteCount,
})

const before = time(`0.3 JSON event per fix (${FIXES} fixes)`, () => {
  for (const text of jsonEvents) {
    const event = JSON.parse(text)
    event.locations.forEach(onLocation)
  }
})
const afterSingle = time(`0.4 typed struct per fix (${FIXES} fixes)`, () => {
  for (const location of fixes) onLocation(fromStruct(location))
})
const afterBatched = time(`0.4 typed batches of ${BATCH} (${FIXES} fixes)`, () => {
  for (const batch of batches) batch.map(fromStruct).forEach(onLocation)
})

// ---------- 2. Pending queue ----------
const EVENTS = 2_000
const MAX = 500
const payload = JSON.stringify({ locations: [fix(1)] })
const directory = mkdtempSync(join(tmpdir(), 'munim-location-bench-'))

const queueBefore = time(`0.3 read-modify-write queue (${EVENTS} events)`, () => {
  const file = join(directory, 'pending-events.json')
  writeFileSync(file, '[]')
  for (let index = 0; index < EVENTS; index++) {
    const events = JSON.parse(readFileSync(file, 'utf8'))
    events.push({ name: 'backgroundLocation', payload, timestamp: Date.now() })
    writeFileSync(file, JSON.stringify(events.length > MAX ? events.slice(-MAX) : events))
  }
})
const queueAfter = time(`0.4 append-only queue (${EVENTS} events)`, () => {
  const file = join(directory, 'pending-events.ndjson')
  writeFileSync(file, '')
  let count = 0
  for (let index = 0; index < EVENTS; index++) {
    appendFileSync(file, JSON.stringify({ name: 'backgroundLocation', payload, timestamp: Date.now() }) + '\n')
    count += 1
    if (count > MAX + MAX / 2) {
      const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean).slice(-MAX)
      writeFileSync(file, lines.join('\n') + '\n')
      count = lines.length
    }
  }
})
rmSync(directory, { recursive: true, force: true })

const rows = [before, afterSingle, afterBatched, queueBefore, queueAfter]
console.log(`node ${process.version}, ${process.platform}/${process.arch}`)
for (const row of rows) console.log(`${row.label.padEnd(48)} ${row.medianMs.toFixed(2).padStart(9)} ms`)
console.log(`delivery speed-up per fix (batched vs 0.3): ${(before.medianMs / afterBatched.medianMs).toFixed(1)}x`)
console.log(`JS wake-ups for ${FIXES} fixes: ${FIXES} (0.3) vs ${batches.length} (batchSize ${BATCH})`)
console.log(`queue speed-up: ${(queueBefore.medianMs / queueAfter.medianMs).toFixed(1)}x`)
if (sink === 0) console.log('')
