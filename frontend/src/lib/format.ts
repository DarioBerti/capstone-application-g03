// Presentation helpers. Money is in CENTS on the wire; times/dates are ISO strings.
const LOCALE = 'en-GB'

export function formatCents(cents: number, currency = 'EUR'): string {
  return new Intl.NumberFormat(LOCALE, { style: 'currency', currency }).format(cents / 100)
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(LOCALE, { hour: '2-digit', minute: '2-digit', hour12: false })
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(LOCALE, { day: '2-digit', month: 'short', year: 'numeric' })
}

// Compact day + month for a trip's date line, e.g. "24 Jul" (no year — results sit within a near window).
export function formatDayMonth(iso: string): string {
  return new Date(iso).toLocaleDateString(LOCALE, { day: '2-digit', month: 'short' })
}

function toIsoDay(dt: Date): string {
  const mm = String(dt.getMonth() + 1).padStart(2, '0')
  const dd = String(dt.getDate()).padStart(2, '0')
  return `${dt.getFullYear()}-${mm}-${dd}`
}

// Calendar-day math on plain 'YYYY-MM-DD' strings (no time/zone). Parse as a LOCAL date so a day never
// shifts across a UTC boundary, then re-serialize — string ordering of the result stays chronological.
export function addDays(isoDay: string, delta: number): string {
  const [y, m, d] = isoDay.split('-').map(Number)
  return toIsoDay(new Date(y, m - 1, d + delta))
}

// The user's local calendar day as 'YYYY-MM-DD' (comparable/composable with addDays).
export function todayIso(): string {
  return toIsoDay(new Date())
}

// The parts a day-strip cell renders for a 'YYYY-MM-DD' day, e.g. { weekday: 'Fri', day: 3, month: 'Jul' }.
export function describeDay(isoDay: string): { weekday: string; day: number; month: string } {
  const [y, m, d] = isoDay.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  return {
    weekday: dt.toLocaleDateString(LOCALE, { weekday: 'short' }),
    day: dt.getDate(),
    month: dt.toLocaleDateString(LOCALE, { month: 'short' }),
  }
}

// Human duration between two ISO instants, e.g. "3h 19m".
export function formatDuration(departIso: string, arriveIso: string): string {
  const mins = Math.max(0, Math.round((new Date(arriveIso).getTime() - new Date(departIso).getTime()) / 60000))
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return h > 0 ? `${h}h ${m.toString().padStart(2, '0')}m` : `${m}m`
}

// mm:ss countdown from a remaining-seconds value (server-authoritative; UI displays only).
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${m.toString().padStart(2, '0')}:${r.toString().padStart(2, '0')}`
}
