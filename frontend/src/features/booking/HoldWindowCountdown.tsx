import Icon from '../../components/Icon'
import { formatCountdown } from '../../lib/format'
import { useCountdown } from './useCountdown'

// Compact inline countdown used in the summary sidebar. Server-seeded (display-only). Turns danger when the
// hold window drops under a minute.
export default function HoldWindowCountdown({ remainingSeconds }: { remainingSeconds: number | null | undefined }) {
  const seconds = useCountdown(remainingSeconds)
  if (seconds == null) return null
  const urgent = seconds <= 60
  return (
    <p className="mt-md text-center font-body-sm text-body-sm text-on-surface-variant">
      <Icon name="timer" className={`text-sm mr-xs ${urgent ? 'text-error' : 'text-primary'}`} />
      Prices are guaranteed for{' '}
      <span className={`tabular-nums font-bold ${urgent ? 'text-error step-pulse inline-block' : ''}`}>
        {formatCountdown(seconds)}
      </span>
    </p>
  )
}
