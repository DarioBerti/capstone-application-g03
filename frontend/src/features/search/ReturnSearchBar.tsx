import Icon from '../../components/Icon'
import type { TimeOfDay } from './returnSearch'

const TIMES: { key: TimeOfDay; label: string }[] = [
  { key: 'any', label: 'Any' },
  { key: 'morning', label: 'Morning' },
  { key: 'afternoon', label: 'Afternoon' },
  { key: 'evening', label: 'Evening' },
]

// Shown once an outbound is held: the return runs the reverse route, and the traveller picks its own date and
// time-of-day here (independent of the outbound). Date is bounded below by the outbound's service date.
export default function ReturnSearchBar({
  route,
  date,
  onDate,
  minDate,
  timeOfDay,
  onTimeOfDay,
}: {
  route: { from: string; to: string }
  date: string
  onDate: (d: string) => void
  minDate: string
  timeOfDay: TimeOfDay
  onTimeOfDay: (t: TimeOfDay) => void
}) {
  return (
    <div className="bg-secondary-container/40 border border-outline-variant/30 rounded-xl p-lg space-y-md">
      <div className="flex items-center gap-sm text-on-secondary-container">
        <Icon name="swap_horiz" className="text-primary" />
        <p className="font-body-sm text-body-sm">
          Outbound held. Pick a return trip to make it round-trip, or proceed to checkout for one-way.
        </p>
      </div>
      <div className="flex flex-col md:flex-row md:items-end gap-md">
        <div className="flex items-center gap-sm">
          <span className="font-title-lg text-title-lg text-on-background">{route.from}</span>
          <Icon name="trending_flat" className="text-primary" />
          <span className="font-title-lg text-title-lg text-on-background">{route.to}</span>
        </div>
        <div className="space-y-xs">
          <label className="font-label-caps text-label-caps text-on-surface-variant block ml-xs" htmlFor="return-date">
            RETURN DATE
          </label>
          <div className="relative">
            <Icon name="calendar_today" className="absolute left-md top-1/2 -translate-y-1/2 text-primary" />
            <input
              id="return-date"
              type="date"
              value={date}
              min={minDate}
              onChange={(e) => onDate(e.target.value)}
              className="pl-xl pr-md py-sm bg-surface-container-lowest border border-outline-variant rounded-lg focus:ring-2 focus:ring-primary focus:outline-none tabular-nums"
            />
          </div>
        </div>
        <div className="space-y-xs">
          <span className="font-label-caps text-label-caps text-on-surface-variant block ml-xs">DEPARTURE</span>
          <div className="flex gap-xs bg-surface-container p-xs rounded-lg" role="group" aria-label="Return time of day">
            {TIMES.map((t) => {
              const active = t.key === timeOfDay
              return (
                <button
                  key={t.key}
                  aria-pressed={active}
                  onClick={() => onTimeOfDay(t.key)}
                  className={`px-md py-sm rounded-md transition-colors font-bold ${
                    active ? 'bg-surface text-primary shadow-sm' : 'text-on-surface-variant hover:bg-surface-container-high'
                  }`}
                >
                  {t.label}
                </button>
              )
            })}
          </div>
        </div>
      </div>
    </div>
  )
}
