import { useMemo, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import Icon from '../../components/Icon'
import { useListStationsQuery } from '../catalog/catalogApi'

const inputClass =
  'w-full pl-xl pr-md py-lg bg-surface-container-low border-2 border-transparent focus:border-primary ' +
  'focus:outline-none focus:ring-0 rounded-lg font-title-lg text-title-lg transition-all'

// The catalog trip filter keys on station CODE; the datalist lets the user type a name/city which we resolve
// back to its code (falling back to the raw text, which simply won't match server-side).
export default function SearchForm() {
  const navigate = useNavigate()
  const { data: stations = [] } = useListStationsQuery()
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [date, setDate] = useState('')
  const [pax, setPax] = useState(1)

  const nameToCode = useMemo(() => {
    const m = new Map<string, string>()
    for (const s of stations) {
      m.set(s.name.toLowerCase(), s.code)
      m.set(`${s.city}, ${s.country}`.toLowerCase(), s.code)
    }
    return m
  }, [stations])

  const resolve = (text: string) => nameToCode.get(text.trim().toLowerCase()) ?? text.trim()

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    const params = new URLSearchParams()
    const origin = resolve(from)
    const destination = resolve(to)
    if (origin) params.set('origin', origin)
    if (destination) params.set('destination', destination)
    if (date) params.set('serviceDate', date)
    params.set('pax', String(pax))
    navigate(`/search?${params.toString()}`)
  }

  return (
    <div className="mt-xl glass-effect rounded-xl p-md shadow-2xl border border-white/20">
      <form className="grid grid-cols-1 md:grid-cols-12 gap-sm items-end" onSubmit={onSubmit}>
        <StationField
          id="from"
          label="FROM"
          icon="location_on"
          placeholder="Paris, France"
          value={from}
          onChange={setFrom}
          stations={stations}
        />
        <StationField
          id="to"
          label="TO"
          icon="near_me"
          placeholder="Amsterdam, Netherlands"
          value={to}
          onChange={setTo}
          stations={stations}
        />
        <div className="md:col-span-2 space-y-xs">
          <label className="font-label-caps text-label-caps text-on-surface-variant block ml-xs" htmlFor="departure">
            DEPARTURE
          </label>
          <div className="relative">
            <Icon name="calendar_today" className="absolute left-md top-1/2 -translate-y-1/2 text-primary" />
            <input
              id="departure"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className={inputClass}
            />
          </div>
        </div>
        <div className="md:col-span-2 space-y-xs">
          <label className="font-label-caps text-label-caps text-on-surface-variant block ml-xs" htmlFor="passengers">
            PASSENGERS
          </label>
          <div className="relative">
            <Icon name="person" className="absolute left-md top-1/2 -translate-y-1/2 text-primary z-10" />
            <select
              id="passengers"
              value={pax}
              onChange={(e) => setPax(Number(e.target.value))}
              className={`${inputClass} appearance-none`}
            >
              <option value={1}>1 Adult</option>
              <option value={2}>2 Adults</option>
              <option value={3}>3 Adults</option>
              <option value={4}>4 Adults</option>
            </select>
          </div>
        </div>
        <div className="md:col-span-2">
          <button
            type="submit"
            className="w-full h-[68px] motion-gradient text-white rounded-lg flex items-center justify-center gap-sm font-title-lg text-title-lg hover:brightness-110 active:scale-95 transition-all shadow-lg"
          >
            <Icon name="search" />
            <span>Search</span>
          </button>
        </div>
      </form>
    </div>
  )
}

function StationField({
  id,
  label,
  icon,
  placeholder,
  value,
  onChange,
  stations,
}: {
  id: string
  label: string
  icon: string
  placeholder: string
  value: string
  onChange: (v: string) => void
  stations: { code: string; name: string; city: string; country: string }[]
}) {
  const listId = `stations-${id}`
  return (
    <div className="md:col-span-3 space-y-xs">
      <label className="font-label-caps text-label-caps text-on-surface-variant block ml-xs" htmlFor={id}>
        {label}
      </label>
      <div className="relative">
        <Icon name={icon} className="absolute left-md top-1/2 -translate-y-1/2 text-primary z-10" />
        <input
          id={id}
          type="text"
          list={listId}
          placeholder={placeholder}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className={inputClass}
          autoComplete="off"
        />
        <datalist id={listId}>
          {stations.map((s) => (
            <option key={s.code} value={s.name}>
              {s.city}, {s.country}
            </option>
          ))}
        </datalist>
      </div>
    </div>
  )
}
