import { useEffect, useState } from 'react'

// The deadline is the backend-owned `expiresAt` when present; otherwise the server-computed
// `remainingSeconds` anchored to the moment the snapshot arrived. An invalid date falls through
// to the anchor so a bad payload can't render NaN.
function deadlineMs(expiresAt: string | null | undefined, remainingSeconds: number | null | undefined): number | null {
  const parsed = expiresAt ? Date.parse(expiresAt) : NaN
  if (Number.isFinite(parsed)) return parsed
  return remainingSeconds != null ? Date.now() + remainingSeconds * 1000 : null
}

const secondsUntil = (deadline: number) => Math.max(0, Math.round((deadline - Date.now()) / 1000))

// Local per-second ticker for the hold window. It only DISPLAYS the countdown; the backend owns the real
// window (re-checked at pay, swept at expiry). Anchoring to the absolute deadline keeps it correct across
// remounts and stale order snapshots — it never restarts from an old `remainingSeconds`.
export function useCountdown(
  expiresAt: string | null | undefined,
  remainingSeconds: number | null | undefined,
): number | null {
  const [seconds, setSeconds] = useState<number | null>(() => {
    const deadline = deadlineMs(expiresAt, remainingSeconds)
    return deadline == null ? null : secondsUntil(deadline)
  })

  useEffect(() => {
    const deadline = deadlineMs(expiresAt, remainingSeconds)
    if (deadline == null) {
      setSeconds(null)
      return
    }
    const tick = () => setSeconds(secondsUntil(deadline))
    tick()
    const timer = window.setInterval(tick, 1000)
    return () => window.clearInterval(timer)
  }, [expiresAt, remainingSeconds])

  return seconds
}
