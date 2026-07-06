const KEY = 'eurotransit-booking'

// The slim, refresh-durable pointer to the active cart. The order snapshot is NOT stored — the backend is
// authoritative on status and the hold window, so it is always re-fetched by id on load. The idempotency key
// and selected trip ride along so the client-owned idempotency/retry rule survives a refresh.
export interface PersistedBooking {
  orderId: string
  idempotencyKey: string | null
  selectedTripCode: string | null
}

// localStorage can throw (private mode, disabled storage, quota) — persistence is best-effort and never fatal.
export function loadBookingPointer(): PersistedBooking | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<PersistedBooking>
    if (typeof parsed?.orderId !== 'string' || !parsed.orderId) return null
    return {
      orderId: parsed.orderId,
      idempotencyKey: typeof parsed.idempotencyKey === 'string' ? parsed.idempotencyKey : null,
      selectedTripCode: typeof parsed.selectedTripCode === 'string' ? parsed.selectedTripCode : null,
    }
  } catch {
    return null
  }
}

export function saveBookingPointer(pointer: PersistedBooking): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(pointer))
  } catch {
    // best-effort: a failed write just means no cross-refresh cart, never a crash
  }
}

export function clearBookingPointer(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // best-effort
  }
}
