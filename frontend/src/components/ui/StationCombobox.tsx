import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import Icon from '../Icon'
import { useAutoFitFont } from '../../lib/useAutoFitFont'
import type { Station } from '../../types'

const OPTION_LIMIT = 50
const FIELD_FONT_PX = 20 // matches the shared `text-title-lg` role

// Sentinel for the unconstrained search: code '' means "no station filter" and is what the catalog
// trip search expects when a side is left open (browse-all).
export const ANYWHERE: Station = { code: '', name: 'Anywhere', city: 'All stations', country: '' }

// Rank a match: prefix hits (name → city → code) before substring hits, so "mil" surfaces "Milano…" first.
function scoreOf(s: Station, term: string): number {
  const name = s.name.toLowerCase()
  const city = s.city.toLowerCase()
  const code = s.code.toLowerCase()
  if (name.startsWith(term)) return 0
  if (city.startsWith(term)) return 1
  if (code.startsWith(term)) return 2
  if (name.includes(term)) return 3
  if (city.includes(term)) return 4
  if (code.includes(term)) return 5
  return -1
}

function filterStations(stations: Station[], query: string): Station[] {
  const term = query.trim().toLowerCase()
  if (!term) return stations.slice(0, OPTION_LIMIT)
  const scored: Array<{ s: Station; score: number }> = []
  for (const s of stations) {
    const score = scoreOf(s, term)
    if (score >= 0) scored.push({ s, score })
  }
  scored.sort((a, b) => a.score - b.score || a.s.name.localeCompare(b.s.name))
  return scored.slice(0, OPTION_LIMIT).map((x) => x.s)
}

export default function StationCombobox({
  id,
  label,
  icon,
  placeholder,
  stations,
  value,
  onChange,
  anywhereOption = false,
}: {
  id: string
  label: string
  icon: string
  placeholder: string
  stations: Station[]
  value: string
  onChange: (code: string) => void
  anywhereOption?: boolean
}) {
  const selected = useMemo(
    () => (anywhereOption && value === '' ? ANYWHERE : (stations.find((s) => s.code === value) ?? null)),
    [stations, value, anywhereOption],
  )
  const [query, setQuery] = useState(selected?.name ?? '')
  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)

  // Reflect the selected station's name into the field whenever the selection changes (pick, reset, clear).
  useEffect(() => {
    setQuery(selected?.name ?? '')
  }, [selected])

  const results = useMemo(() => {
    if (!anywhereOption) return filterStations(stations, query)
    const term = query.trim().toLowerCase()
    // An untouched query (empty, or still mirroring the current selection) means the user is browsing:
    // lead with "Anywhere" over the full list. Typing towards "Anywhere" keeps it; any other term is a
    // plain station search.
    const browsing = !term || term === selected?.name.toLowerCase()
    if (browsing || ANYWHERE.name.toLowerCase().startsWith(term)) {
      return [ANYWHERE, ...filterStations(stations, browsing ? '' : query).slice(0, OPTION_LIMIT - 1)]
    }
    return filterStations(stations, query)
  }, [stations, query, anywhereOption, selected])
  useEffect(() => setActiveIndex(0), [query])

  const { ref: inputRef, fontPx } = useAutoFitFont<HTMLInputElement>(query || placeholder, FIELD_FONT_PX)
  const listId = `stations-${id}`

  const close = () => {
    setOpen(false)
    setQuery(selected?.name ?? '') // drop any unmatched free text — only a real station counts
  }
  const pick = (s: Station) => {
    onChange(s.code)
    setOpen(false)
  }

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) close()
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }) // re-bind each render so `close` sees the current `selected`

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      close()
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
      setActiveIndex((i) => Math.min(i + 1, results.length - 1))
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((i) => Math.max(i - 1, 0))
      return
    }
    // Enter selects the active option only while the list is open; otherwise it falls through to submit the form.
    if (e.key === 'Enter' && open && results[activeIndex]) {
      e.preventDefault()
      pick(results[activeIndex])
    }
  }

  return (
    <div className="md:col-span-1 space-y-xs">
      <label className="font-label-caps text-label-caps text-on-surface-variant block ml-xs" htmlFor={id}>
        {label}
      </label>
      <div className="relative" ref={rootRef}>
        <Icon name={icon} className="absolute left-md top-1/2 -translate-y-1/2 text-primary z-10 pointer-events-none" />
        <input
          ref={inputRef}
          id={id}
          type="text"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          autoComplete="off"
          placeholder={placeholder}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
            if (e.target.value.trim() === '' && value) onChange('')
          }}
          onFocus={(e) => {
            setOpen(true)
            e.target.select()
          }}
          onKeyDown={onKeyDown}
          style={{ fontSize: `${fontPx}px` }}
          className="w-full h-[68px] pl-[52px] pr-md bg-surface-container-low border-2 border-transparent focus:border-primary focus:outline-none focus:ring-0 rounded-lg font-title-lg text-on-surface placeholder:text-outline transition-all"
        />
        {open && results.length > 0 && (
          <ul
            id={listId}
            role="listbox"
            className="absolute z-50 top-full left-0 mt-2 w-full max-h-72 overflow-auto bg-surface rounded-xl shadow-2xl border border-outline-variant p-xs"
          >
            {results.map((s, i) => (
              <li key={s.code} role="option" aria-selected={i === activeIndex}>
                <button
                  type="button"
                  onMouseEnter={() => setActiveIndex(i)}
                  onClick={() => pick(s)}
                  className={`w-full text-left px-md py-sm rounded-lg transition-colors ${
                    i === activeIndex ? 'bg-primary/10' : 'hover:bg-surface-container-low'
                  }`}
                >
                  <span className="block font-body-md text-body-md text-on-surface truncate">{s.name}</span>
                  <span className="block font-body-sm text-body-sm text-on-surface-variant truncate">
                    {s.country ? `${s.city}, ${s.country}` : s.city}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
