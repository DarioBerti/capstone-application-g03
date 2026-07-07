import { useEffect, useState } from 'react'
import Icon from '../../components/Icon'
import Spinner from '../../components/ui/Spinner'

// Whole-booking discard: a quiet text button that expands to an inline two-step confirm (no modal, KISS) so
// releasing a live hold can't happen on a single stray tap. The confirm action drops every held leg. Shared by
// the search-page cart (BookingSummary) and the checkout page — hence self-contained confirm state, collapsed
// whenever `resetKey` (the active order id) changes so an armed confirm never carries across bookings.
export default function DiscardBooking({
  onConfirm,
  removing,
  resetKey,
  className = '',
}: {
  onConfirm: () => void | Promise<void>
  removing: boolean
  resetKey?: string
  className?: string
}) {
  const [confirming, setConfirming] = useState(false)
  useEffect(() => setConfirming(false), [resetKey])

  if (!confirming) {
    return (
      <button
        onClick={() => setConfirming(true)}
        className={`w-full py-sm font-body-sm text-body-sm text-on-surface-variant hover:text-error transition-colors flex items-center justify-center gap-xs ${className}`}
      >
        <Icon name="delete" className="text-base" />
        Discard booking
      </button>
    )
  }
  return (
    <div className={`rounded-lg border border-error/30 bg-error/5 p-md ${className}`}>
      <p className="font-body-sm text-body-sm text-on-surface text-center mb-sm">
        Discard this booking and release the held seats?
      </p>
      <div className="flex gap-sm">
        <button
          onClick={() => setConfirming(false)}
          disabled={removing}
          className="flex-1 py-sm rounded-lg bg-surface-container text-on-surface font-bold hover:bg-surface-container-high transition-colors disabled:opacity-50"
        >
          Keep
        </button>
        <button
          onClick={() => void onConfirm()}
          disabled={removing}
          className="flex-1 py-sm rounded-lg bg-error text-on-error font-bold hover:brightness-110 transition-all disabled:opacity-50 flex items-center justify-center gap-xs"
        >
          {removing ? <Spinner className="text-sm" /> : 'Discard'}
        </button>
      </div>
    </div>
  )
}
