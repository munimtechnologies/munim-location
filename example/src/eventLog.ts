/**
 * Tiny in-memory log shared by the background handler (registered in
 * index.js) and the screens.
 */
export interface LogEntry {
  id: number
  time: string
  name: string
  detail: string
}

type Listener = (entries: LogEntry[]) => void

let entries: LogEntry[] = []
let nextId = 1
const listeners = new Set<Listener>()

export function log(name: string, detail: unknown = '') {
  const text =
    typeof detail === 'string' ? detail : JSON.stringify(detail, null, 0)
  entries = [
    {
      id: nextId++,
      time: new Date().toLocaleTimeString(),
      name,
      detail: text.length > 400 ? `${text.slice(0, 400)}…` : text,
    },
    ...entries,
  ].slice(0, 200)
  listeners.forEach(listener => listener(entries))
}

export function clearLog() {
  entries = []
  listeners.forEach(listener => listener(entries))
}

export function subscribeLog(listener: Listener): () => void {
  listeners.add(listener)
  listener(entries)
  return () => {
    listeners.delete(listener)
  }
}
