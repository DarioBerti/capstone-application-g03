import { useEffect, useState } from 'react'

// Local per-second ticker seeded from the server-authoritative `remainingSeconds`. It only DISPLAYS the
// countdown; the backend owns the real hold window (re-checked at pay, swept at expiry). Whenever a fresh
// order snapshot arrives (remainingSeconds changes) it resyncs, so the UI never drifts from the server.
export function useCountdown(remainingSeconds: number | null | undefined): number | null {
  const [seconds, setSeconds] = useState<number | null>(remainingSeconds ?? null)

  useEffect(() => {
    if (remainingSeconds == null) {
      setSeconds(null)
      return
    }
    setSeconds(remainingSeconds)
    const timer = window.setInterval(() => {
      setSeconds((s) => (s != null && s > 0 ? s - 1 : s))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [remainingSeconds])

  return seconds
}
