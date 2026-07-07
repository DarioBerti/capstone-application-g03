import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from '../../components/Icon'
import { isAuthenticated } from '../../auth'
import { formatCents, formatCountdown } from '../../lib/format'
import { useListMyOrdersQuery } from './ordersApi'
import type { Order } from '../../types'

const POLL_MS = 30_000
// The CheckoutExpirySweeper runs every 5s server-side; refresh just after it so an expired hold
// disappears promptly instead of waiting for the next regular poll.
const EXPIRY_REFRESH_MS = 6_000

export default function CartIndicator() {
  const authed = isAuthenticated()
  const { data, fulfilledTimeStamp, refetch } = useListMyOrdersQuery(
    { status: 'HELD', size: 10 },
    { skip: !authed, pollingInterval: POLL_MS },
  )
  const [open, setOpen] = useState(false)
  const [nowMs, setNowMs] = useState(() => Date.now())
  const rootRef = useRef<HTMLDivElement>(null)

  // Pre-rollout backends ignore the status param and return every order; filter defensively.
  const held = useMemo(() => (data?.content ?? []).filter((o) => o.status === 'HELD'), [data])

  // Display-only countdown: seed from the server-authoritative remainingSeconds at fetch time and age it
  // locally. The backend re-checks at pay and sweeps at expiry — this never decides anything.
  const remainingOf = (o: Order): number => {
    if (o.remainingSeconds == null) return 0
    const elapsed = fulfilledTimeStamp ? Math.max(0, Math.floor((nowMs - fulfilledTimeStamp) / 1000)) : 0
    return Math.max(0, o.remainingSeconds - elapsed)
  }
  const pending = held.filter((o) => remainingOf(o) > 0)
  const soonest = pending.length ? Math.min(...pending.map(remainingOf)) : null

  useEffect(() => {
    if (!held.length) return
    const timer = window.setInterval(() => setNowMs(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [held.length])

  // A hold just ran out: give the sweeper a beat, then re-sync so the indicator empties.
  const allExpired = held.length > 0 && pending.length === 0
  useEffect(() => {
    if (!allExpired) return
    const timer = window.setTimeout(() => refetch(), EXPIRY_REFRESH_MS)
    return () => window.clearTimeout(timer)
  }, [allExpired, refetch])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  if (!authed || pending.length === 0) return null

  const urgent = soonest != null && soonest <= 60

  return (
    <div className="relative" ref={rootRef}>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`${pending.length} pending ${pending.length === 1 ? 'purchase' : 'purchases'}, ${formatCountdown(soonest ?? 0)} left`}
        className="relative flex items-center gap-xs h-9 pl-sm pr-md rounded-full bg-surface-container text-on-surface hover:bg-surface-container-high transition-colors"
      >
        <Icon name="shopping_cart" className={urgent ? 'text-error' : 'text-primary'} />
        <span
          className={`font-label-tabular text-body-sm tabular-nums font-bold ${urgent ? 'text-error' : 'text-on-surface'}`}
        >
          {formatCountdown(soonest ?? 0)}
        </span>
        <span className="absolute -top-1 -right-1 min-w-[18px] h-[18px] px-[5px] rounded-full bg-accent text-on-tertiary-fixed text-[11px] leading-[18px] font-bold text-center">
          {pending.length}
        </span>
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="Pending purchases"
          className="absolute right-0 top-full mt-2 w-80 max-w-[90vw] z-50 bg-surface rounded-xl shadow-2xl border border-outline-variant p-sm"
        >
          <p className="px-sm py-xs font-label-caps text-label-caps text-on-surface-variant">
            PENDING {pending.length === 1 ? 'PURCHASE' : 'PURCHASES'}
          </p>
          <ul className="space-y-xs">
            {pending.map((order) => {
              const legs = order.legs.filter((l) => l.status !== 'RELEASED')
              const remaining = remainingOf(order)
              return (
                <li key={order.orderId}>
                  <Link
                    to={`/orders/${order.orderId}`}
                    onClick={() => setOpen(false)}
                    className="flex items-center justify-between gap-sm px-sm py-sm rounded-lg hover:bg-surface-container-low transition-colors"
                  >
                    <span className="min-w-0">
                      <span className="block font-body-md text-body-md text-on-surface truncate">
                        {legs.map((l) => l.tripCode).join(' · ') || 'Empty booking'}
                      </span>
                      <span className="block font-body-sm text-body-sm text-on-surface-variant">
                        {legs.length} {legs.length === 1 ? 'leg' : 'legs'} ·{' '}
                        {formatCents(order.amountCents, order.currency)}
                      </span>
                    </span>
                    <span
                      className={`shrink-0 flex items-center gap-xs font-label-tabular text-body-sm tabular-nums font-bold ${
                        remaining <= 60 ? 'text-error' : 'text-primary'
                      }`}
                    >
                      <Icon name="timer" className="text-sm" />
                      {formatCountdown(remaining)}
                    </span>
                  </Link>
                </li>
              )
            })}
          </ul>
          <p className="px-sm pt-xs pb-xs font-body-sm text-body-sm text-on-surface-variant border-t border-outline-variant mt-xs">
            Seats are released when the hold expires.
          </p>
        </div>
      )}
    </div>
  )
}
