export type SortKey = 'cheapest' | 'fastest'

const TABS: { key: SortKey; label: string }[] = [
  { key: 'cheapest', label: 'Cheapest' },
  { key: 'fastest', label: 'Fastest' },
]

export default function SortTabs({ value, onChange }: { value: SortKey; onChange: (k: SortKey) => void }) {
  return (
    <div className="flex gap-xs bg-surface-container p-xs rounded-lg" role="tablist" aria-label="Sort results">
      {TABS.map((t) => {
        const active = t.key === value
        return (
          <button
            key={t.key}
            role="tab"
            aria-selected={active}
            onClick={() => onChange(t.key)}
            className={`px-lg py-sm rounded-md transition-colors font-bold ${
              active ? 'bg-surface text-primary shadow-sm' : 'text-on-surface-variant hover:bg-surface-container-high'
            }`}
          >
            {t.label}
          </button>
        )
      })}
    </div>
  )
}
