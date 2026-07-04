import { getToken } from './auth'
import type { Order } from './types'

// Streams a booking's status snapshots from the Notifications SSE hub. We can't use EventSource here: it can't
// set the Authorization header the resource server requires, and we won't leak the token in the URL. So we read
// the text/event-stream over fetch() + ReadableStream and parse SSE by hand — split on the blank-line frame
// separator, keep `data:` lines, ignore `:` heartbeat comments. Resolves when the server ends the stream (the
// booking reached a terminal state, or the emitter lapsed); rejects on a network/HTTP error so the caller can
// reconnect or fall back to polling. `signal` (AbortController) tears the connection down on unmount.
export async function streamOrder(
  orderId: string,
  onOrder: (order: Order) => void,
  signal: AbortSignal,
): Promise<void> {
  const token = await getToken()
  const headers = new Headers({ Accept: 'text/event-stream' })
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const res = await fetch(`/api/notifications/orders/${orderId}/events`, { headers, signal })
  if (!res.ok || !res.body) {
    throw new Error(`SSE ${res.status} ${res.statusText}`)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) return
    buffer += decoder.decode(value, { stream: true })
    let sep: number
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      const data = frame
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('\n')
      if (!data) continue // a heartbeat comment (`:ping`) or a frame with no data payload
      try {
        onOrder(JSON.parse(data) as Order)
      } catch {
        // Ignore a partial/garbled frame; the next complete frame carries the current state.
      }
    }
  }
}
