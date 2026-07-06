import DateStrip from './DateStrip'
import type { TimeOfDay } from './returnSearch'

const TIMES: { key: TimeOfDay; label: string }[] = [
  { key: 'any', label: 'Any' },
  { key: 'morning', label: 'Morning' },
  { key: 'afternoon', label: 'Afternoon' },
  { key: 'evening', label: 'Evening' },
]

// Shown once an outbound is held: the return runs the reverse route on its own date (picked from the day
// strip, bounded below by the outbound's service date) and time-of-day.
export default function ReturnSearchBar({
  date,
  onDate,
  minDate,
  timeOfDay,
  onTimeOfDay,
}: {
  date: string
  onDate: (d: string) => void
  minDate: string
  timeOfDay: TimeOfDay
  onTimeOfDay: (t: TimeOfDay) => void
}) {
  return (
    <div className="space-y-md">
      <DateStrip value={date} onChange={onDate} minDate={minDate} />
      <div className="flex items-center justify-end gap-sm">
        <span className="font-label-caps text-label-caps text-on-surface-variant">DEPARTURE</span>
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
  )
}
