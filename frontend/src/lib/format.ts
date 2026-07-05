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
