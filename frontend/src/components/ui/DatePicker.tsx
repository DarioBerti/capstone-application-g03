import { useEffect, useMemo, useRef, useState } from 'react'
import Icon from '../Icon'

const WEEKDAYS = ['M', 'T', 'W', 'T', 'F', 'S', 'S']

// Local (timezone-safe) ISO yyyy-mm-dd — never via toISOString(), which shifts to UTC and can land on the
// wrong calendar day near midnight.
function toIso(year: number, month: number, day: number): string {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function parseIso(iso: string): Date | null {
  const [y, m, d] = iso.split('-').map(Number)
  if (!y || !m || !d) return null
  return new Date(y, m - 1, d)
}

function startOfToday(): Date {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth(), now.getDate())
}

// Monday-first weekday index (0 = Mon … 6 = Sun).
function mondayIndex(date: Date): number {
  return (date.getDay() + 6) % 7
}

type DayCell = { day: number; iso: string; inMonth: boolean; disabled: boolean }

// The grid: leading days from the previous month (muted) followed by every day of the shown month.
function buildMonth(view: Date, min: Date | null): DayCell[] {
  const year = view.getFullYear()
  const month = view.getMonth()
  const lead = mondayIndex(new Date(year, month, 1))
  const prevMonthDays = new Date(year, month, 0).getDate()
  const daysInMonth = new Date(year, month + 1, 0).getDate()

  const cells: DayCell[] = []
  for (let i = lead - 1; i >= 0; i--) {
    const day = prevMonthDays - i
    const dt = new Date(year, month - 1, day)
    cells.push({ day, iso: toIso(dt.getFullYear(), dt.getMonth(), day), inMonth: false, disabled: true })
  }
  for (let day = 1; day <= daysInMonth; day++) {
    cells.push({ day, iso: toIso(year, month, day), inMonth: true, disabled: min ? new Date(year, month, day) < min : false })
  }
  return cells
}

export default function DatePicker({
  id,
  value,
  onChange,
  placeholder = 'Select date',
  min,
  disablePast = false,
  className = '',
  icon = 'calendar_today',
}: {
  id?: string
  value: string
  onChange: (iso: string) => void
  placeholder?: string
  min?: string
  disablePast?: boolean
  className?: string
  icon?: string
}) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  const selected = useMemo(() => parseIso(value), [value])
  const minDate = useMemo(() => {
    const explicit = parseIso(min ?? '')
    const floor = disablePast ? startOfToday() : null
    if (explicit && floor) return explicit > floor ? explicit : floor
    return explicit ?? floor
  }, [min, disablePast])

  const [view, setView] = useState<Date>(() => selected ?? minDate ?? new Date())
  // Follow an externally-set value (e.g. reset) to its month when the popup is reopened.
  useEffect(() => {
    if (selected) setView(selected)
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps

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

  const cells = useMemo(() => buildMonth(view, minDate), [view, minDate])
  const triggerLabel = selected
    ? selected.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
    : placeholder
  const monthLabel = view.toLocaleDateString('en-GB', { month: 'long', year: 'numeric' })

  const pick = (iso: string) => {
    onChange(iso)
    setOpen(false)
  }
  const shiftMonth = (delta: number) => setView((v) => new Date(v.getFullYear(), v.getMonth() + delta, 1))

  return (
    <div className="relative" ref={rootRef}>
      <Icon name={icon} className="absolute left-md top-1/2 -translate-y-1/2 text-primary z-10 pointer-events-none" />
      <button
        type="button"
        id={id}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="dialog"
        aria-expanded={open}
        className={`${className} flex items-center text-left truncate ${selected ? 'text-on-surface' : 'text-outline'}`}
      >
        {triggerLabel}
      </button>
      {open && (
        <div
          role="dialog"
          aria-label="Choose date"
          className="absolute z-50 top-full left-0 mt-2 w-72 max-w-[90vw] bg-surface rounded-xl shadow-2xl border border-outline-variant p-md"
        >
          <div className="flex justify-between items-center mb-md">
            <button
              type="button"
              onClick={() => shiftMonth(-1)}
              aria-label="Previous month"
              className="p-xs hover:bg-surface-container-low rounded-full transition-colors"
            >
              <Icon name="chevron_left" className="text-on-surface-variant" />
            </button>
            <span className="font-title-lg text-title-lg text-on-surface">{monthLabel}</span>
            <button
              type="button"
              onClick={() => shiftMonth(1)}
              aria-label="Next month"
              className="p-xs hover:bg-surface-container-low rounded-full transition-colors"
            >
              <Icon name="chevron_right" className="text-on-surface-variant" />
            </button>
          </div>
          <div className="grid grid-cols-7 gap-xs text-center mb-sm">
            {WEEKDAYS.map((w, i) => (
              <span key={i} className="font-label-caps text-label-caps text-on-surface-variant">
                {w}
              </span>
            ))}
          </div>
          <div className="grid grid-cols-7 gap-xs text-center">
            {cells.map((c) => {
              if (!c.inMonth || c.disabled) {
                return (
                  <div key={c.iso} className="py-sm text-on-surface-variant/30">
                    {c.day}
                  </div>
                )
              }
              if (c.iso === value) {
                return (
                  <div key={c.iso} className="py-sm bg-primary text-on-primary rounded-full font-bold shadow-md">
                    {c.day}
                  </div>
                )
              }
              return (
                <button
                  key={c.iso}
                  type="button"
                  onClick={() => pick(c.iso)}
                  className="py-sm cursor-pointer hover:bg-primary/10 rounded-full transition-colors"
                >
                  {c.day}
                </button>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
