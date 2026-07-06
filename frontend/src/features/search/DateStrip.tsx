import { useEffect, useRef, useState } from 'react'
import Icon from '../../components/Icon'
import { addDays, describeDay } from '../../lib/format'

const VISIBLE_DAYS = 7
// Keep the selected day roughly centred in the window without ever paging before the earliest allowed day.
const CENTER_OFFSET = 3

// Horizontal day picker for the return date (replaces a plain date input). Days before `minDate` (the
// outbound's service date) are not selectable; the arrows page the window a week at a time and the calendar
// button opens the native picker for an arbitrary jump. Operates on plain 'YYYY-MM-DD' strings.
export default function DateStrip({
  value,
  onChange,
  minDate,
}: {
  value: string
  onChange: (day: string) => void
  minDate: string
}) {
  const clampStart = (day: string) => (day < minDate ? minDate : day)
  const [windowStart, setWindowStart] = useState(() => clampStart(addDays(value, -CENTER_OFFSET)))
  const dateInputRef = useRef<HTMLInputElement>(null)

  // Recenter when the selection drifts outside the visible window (e.g. a calendar jump or an outbound change).
  useEffect(() => {
    const windowEnd = addDays(windowStart, VISIBLE_DAYS - 1)
    if (value < windowStart || value > windowEnd) setWindowStart(clampStart(addDays(value, -CENTER_OFFSET)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, minDate])

  const days = Array.from({ length: VISIBLE_DAYS }, (_, i) => addDays(windowStart, i))
  const atMin = windowStart <= minDate

  const openCalendar = () => {
    const input = dateInputRef.current
    if (!input) return
    if (input.showPicker) input.showPicker()
    else input.focus()
  }

  return (
    <div className="bg-surface-container-lowest border border-outline-variant/30 rounded-xl shadow-sm p-sm">
      <div className="flex items-center justify-between gap-md">
        <button
          type="button"
          onClick={() => setWindowStart((w) => clampStart(addDays(w, -VISIBLE_DAYS)))}
          disabled={atMin}
          aria-label="Previous days"
          className="w-10 h-10 shrink-0 rounded-full bg-primary text-on-primary flex items-center justify-center active:scale-95 transition-all disabled:opacity-40 disabled:pointer-events-none"
        >
          <Icon name="arrow_back" />
        </button>

        <div className="flex-grow flex items-center justify-center gap-sm overflow-x-auto">
          {days.map((day) => {
            const { weekday, day: dayNum, month } = describeDay(day)
            const selected = day === value
            if (selected) {
              return (
                <button
                  key={day}
                  type="button"
                  aria-pressed
                  aria-label={`${weekday} ${dayNum} ${month}`}
                  className="flex flex-col items-center justify-center min-w-[80px] rounded-xl bg-primary text-on-primary shadow-lg scale-110 z-10 p-sm"
                >
                  <span className="font-label-caps text-label-caps opacity-80">{weekday}</span>
                  <span className="text-headline-md font-bold leading-none my-1">{dayNum}</span>
                  <span className="font-label-caps text-label-caps font-bold">{month}</span>
                </button>
              )
            }
            return (
              <button
                key={day}
                type="button"
                onClick={() => onChange(day)}
                aria-label={`${weekday} ${dayNum} ${month}`}
                className="flex flex-col items-center justify-center min-w-[70px] rounded-lg hover:bg-surface-container transition-colors cursor-pointer p-xs"
              >
                <span className="font-label-caps text-label-caps text-on-surface-variant">{weekday}</span>
                <span className="font-headline-md text-headline-md text-on-surface">{dayNum}</span>
                <span className="font-label-caps text-label-caps text-on-surface-variant">{month}</span>
              </button>
            )
          })}
        </div>

        <button
          type="button"
          onClick={() => setWindowStart((w) => addDays(w, VISIBLE_DAYS))}
          aria-label="Next days"
          className="w-10 h-10 shrink-0 rounded-full bg-primary text-on-primary flex items-center justify-center active:scale-95 transition-all"
        >
          <Icon name="arrow_forward" />
        </button>

        <div className="relative shrink-0">
          <button
            type="button"
            onClick={openCalendar}
            className="flex items-center gap-xs px-md py-sm border border-outline-variant rounded-lg text-primary hover:bg-surface-container transition-all active:scale-95"
          >
            <Icon name="calendar_month" />
            <span className="font-label-caps text-label-caps hidden sm:inline">CALENDAR</span>
          </button>
          <input
            ref={dateInputRef}
            type="date"
            value={value}
            min={minDate}
            onChange={(e) => e.target.value && onChange(e.target.value)}
            aria-label="Pick a return date"
            className="absolute inset-0 w-full h-full opacity-0 pointer-events-none"
            tabIndex={-1}
          />
        </div>
      </div>
    </div>
  )
}
