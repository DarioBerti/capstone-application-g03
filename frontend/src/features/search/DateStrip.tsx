import { useEffect, useRef, useState } from 'react'
import Icon from '../../components/Icon'
import { addDays, describeDay, formatCents } from '../../lib/format'
import { useListTripsQuery } from '../catalog/catalogApi'

const VISIBLE_DAYS = 7
// Keep the selected day roughly centred in the window without ever paging before the earliest allowed day.
const CENTER_OFFSET = 3
// The services' max page size — large enough to always catch a day's cheapest fare on this catalog.
const DAY_PRICE_PAGE_SIZE = 100

export interface DayPriceQuery {
  origin?: string
  destination?: string
}

// Cheapest fare for one day of the strip's route, read from the same cached public trips endpoint the
// results list uses. Renders nothing while loading or when the day has no trips.
function DayPrice({ query, day, selected }: { query: DayPriceQuery; day: string; selected: boolean }) {
  const { data } = useListTripsQuery({ ...query, serviceDate: day, size: DAY_PRICE_PAGE_SIZE })
  const trips = data?.content ?? []
  if (trips.length === 0) return null
  const cheapest = trips.reduce((min, t) => Math.min(min, t.priceCents), Number.POSITIVE_INFINITY)
  return (
    <span className={`font-body-sm text-body-sm mt-xs ${selected ? 'opacity-80' : 'text-outline'}`}>
      from {formatCents(cheapest, trips[0].currency)}
    </span>
  )
}

// Horizontal day picker (replaces a plain date input). Days before `minDate` are not selectable; the arrows
// page the window a week at a time and the calendar button opens the native picker for an arbitrary jump.
// With a `priceQuery` each day cell also shows the route's cheapest fare. Operates on plain 'YYYY-MM-DD'
// strings.
export default function DateStrip({
  value,
  onChange,
  minDate,
  priceQuery,
}: {
  value: string
  onChange: (day: string) => void
  minDate: string
  priceQuery?: DayPriceQuery
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
    <div className="flex items-center gap-sm">
      <button
        type="button"
        onClick={() => setWindowStart((w) => clampStart(addDays(w, -VISIBLE_DAYS)))}
        disabled={atMin}
        aria-label="Previous days"
        className="w-10 h-10 shrink-0 rounded-full bg-primary text-on-primary flex items-center justify-center active:scale-95 transition-all disabled:opacity-40 disabled:pointer-events-none"
      >
        <Icon name="arrow_back" />
      </button>

      <div className="flex-grow flex items-stretch justify-between gap-sm overflow-x-auto pb-xs">
        {days.map((day) => {
          const { weekday, day: dayNum, month } = describeDay(day)
          const selected = day === value
          return (
            <button
              key={day}
              type="button"
              onClick={() => onChange(day)}
              aria-pressed={selected}
              aria-label={`${weekday} ${dayNum} ${month}`}
              className={`flex flex-col items-center p-md rounded-lg min-w-[100px] transition-colors ${
                selected
                  ? 'bg-primary text-on-primary shadow-sm'
                  : 'bg-surface-container-low text-on-surface-variant border border-outline-variant hover:bg-surface-variant'
              }`}
            >
              <span className={`font-label-caps text-label-caps ${selected ? 'opacity-80' : 'text-outline'}`}>
                {weekday}
              </span>
              <span className="font-title-lg text-title-lg whitespace-nowrap">
                {dayNum} {month}
              </span>
              {priceQuery && <DayPrice query={priceQuery} day={day} selected={selected} />}
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
          aria-label="Pick a date"
          className="absolute inset-0 w-full h-full opacity-0 pointer-events-none"
          tabIndex={-1}
        />
      </div>
    </div>
  )
}
