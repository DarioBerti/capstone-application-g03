import Icon from '../../components/Icon'
import { formatCountdown } from '../../lib/format'
import { useCountdown } from '../booking/useCountdown'

// Prominent hold-window countdown on the checkout page. Server-seeded via `remainingSeconds` (display-only;
// the backend enforces the real window and rejects with EXPIRED_NO_PAYMENT at zero).
export default function HoldWindowCard({ remainingSeconds }: { remainingSeconds: number | null | undefined }) {
  const seconds = useCountdown(remainingSeconds)
  const urgent = seconds != null && seconds <= 60
  return (
    <div className="bg-inverse-surface text-inverse-on-surface p-lg rounded-xl shadow-lg border border-primary/20">
      <div className="flex items-center justify-between mb-sm">
        <span className="font-label-caps text-label-caps opacity-80 uppercase">Hold window</span>
        <Icon name="timer" className={urgent ? 'text-error' : 'text-primary-fixed'} />
      </div>
      <div
        className={`font-display-lg text-display-lg-mobile tracking-tighter tabular-nums ${
          urgent ? 'text-error step-pulse inline-block' : 'text-primary-fixed'
        }`}
      >
        {seconds == null ? '--:--' : formatCountdown(seconds)}
      </div>
      <p className="font-body-sm text-body-sm mt-sm opacity-70">Complete booking before your price expires.</p>
    </div>
  )
}
