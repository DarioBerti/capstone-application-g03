import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import Icon from '../../components/Icon'
import { useListAllStationsQuery } from '../catalog/catalogApi'
import DatePicker from '../../components/ui/DatePicker'
import StationCombobox from '../../components/ui/StationCombobox'

// Shared by the date field and matched by the Search button height so the row reads as one consistent
// control: same font, same box height, equal-width columns.
const inputClass =
  'w-full h-[68px] pl-[52px] pr-md bg-surface-container-low border-2 border-transparent focus:border-primary ' +
  'focus:outline-none focus:ring-0 rounded-lg font-title-lg text-title-lg transition-all'

// The passenger counter has no CSS "empty" state, so we toggle the colour in JS to mirror the text
// inputs: muted while untouched, normal text colour once the user changes it.
const MUTED_TEXT = 'text-outline'
const FILLED_TEXT = 'text-on-surface'

const MIN_PAX = 1
const stepBtnClass =
  'w-8 h-8 shrink-0 flex items-center justify-center rounded-full text-primary bg-surface-container ' +
  'hover:bg-surface-container-high active:scale-95 disabled:opacity-40 disabled:pointer-events-none transition-colors'

export default function SearchForm() {
  const navigate = useNavigate()
  const { data: stations = [] } = useListAllStationsQuery()
  // From/To hold the selected station CODE (the combobox owns name↔code resolution); the code is what the
  // catalog trip filter keys on and what travels in the /search URL.
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [date, setDate] = useState('')
  const [pax, setPax] = useState(1)
  const [paxTouched, setPaxTouched] = useState(false)

  const changePax = (delta: number) => {
    const next = Math.max(MIN_PAX, pax + delta)
    if (next === pax) return
    setPax(next)
    setPaxTouched(true)
  }

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    const params = new URLSearchParams()
    if (from) params.set('origin', from)
    if (to) params.set('destination', to)
    if (date) params.set('serviceDate', date)
    params.set('pax', String(pax))
    navigate(`/search?${params.toString()}`)
  }

  return (
    <div className="mt-xl glass-effect rounded-xl p-md shadow-2xl border border-white/20">
      <form className="grid grid-cols-1 md:grid-cols-5 gap-sm items-end" onSubmit={onSubmit}>
        <StationCombobox
          id="from"
          label="FROM"
          icon="location_on"
          placeholder="Anywhere"
          stations={stations}
          value={from}
          onChange={setFrom}
          anywhereOption
        />
        <StationCombobox
          id="to"
          label="TO"
          icon="near_me"
          placeholder="Anywhere"
          stations={stations}
          value={to}
          onChange={setTo}
          anywhereOption
        />
        <div className="md:col-span-1 space-y-xs">
          <label className="font-label-caps text-label-caps text-on-surface-variant block ml-xs" htmlFor="departure">
            DEPARTURE
          </label>
          <DatePicker
            id="departure"
            value={date}
            onChange={setDate}
            placeholder="dd/mm/aaaa"
            disablePast
            className={inputClass}
          />
        </div>
        <div className="md:col-span-1 space-y-xs">
          <span className="font-label-caps text-label-caps text-on-surface-variant block ml-xs" id="passengers-label">
            PASSENGERS
          </span>
          <div
            role="group"
            aria-labelledby="passengers-label"
            className="relative h-[68px] flex items-center pl-[52px] pr-sm bg-surface-container-low border-2 border-transparent rounded-lg"
          >
            <Icon name="person" className="absolute left-md top-1/2 -translate-y-1/2 text-primary z-10" />
            <span className={`flex-grow whitespace-nowrap font-title-lg text-title-lg ${paxTouched ? FILLED_TEXT : MUTED_TEXT}`}>
              {pax} {pax === 1 ? 'Adult' : 'Adults'}
            </span>
            <div className="flex items-center gap-xs shrink-0">
              <button
                type="button"
                onClick={() => changePax(-1)}
                disabled={pax <= MIN_PAX}
                aria-label="Fewer passengers"
                className={stepBtnClass}
              >
                <Icon name="remove" />
              </button>
              <button
                type="button"
                onClick={() => changePax(1)}
                aria-label="More passengers"
                className={stepBtnClass}
              >
                <Icon name="add" />
              </button>
            </div>
          </div>
        </div>
        <div className="md:col-span-1">
          <button
            type="submit"
            className="w-full h-[68px] motion-gradient text-on-primary rounded-lg flex items-center justify-center gap-sm font-title-lg text-title-lg hover:brightness-110 active:scale-95 transition-all shadow-lg"
          >
            <Icon name="search" />
            <span>Search</span>
          </button>
        </div>
      </form>
    </div>
  )
}
