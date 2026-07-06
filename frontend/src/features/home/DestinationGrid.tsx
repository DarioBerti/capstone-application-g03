import { Link } from 'react-router-dom'
import Icon from '../../components/Icon'
import Badge from '../../components/ui/Badge'
import { useListTopDestinationsQuery } from '../catalog/catalogApi'
import { formatCents } from '../../lib/format'
import type { DestinationSummary } from '../../types'

// Token-based tile art (no external assets): a deterministic gradient pair picked from the palette by
// station code, so every destination keeps a stable identity across renders and themes.
const TILE_ART = [
  'from-primary-container to-inverse-surface',
  'from-secondary-container to-inverse-surface',
  'from-tertiary-container to-inverse-surface',
] as const

function artOf(code: string): string {
  let hash = 0
  for (let i = 0; i < code.length; i++) hash = (hash * 31 + code.charCodeAt(i)) | 0
  return TILE_ART[Math.abs(hash) % TILE_ART.length]
}

export default function DestinationGrid() {
  const { data: destinations = [], isLoading, isError } = useListTopDestinationsQuery()

  // The section is pure inspiration: hide it gracefully rather than surfacing an error on the home page.
  if (isError || (!isLoading && destinations.length === 0)) return null

  return (
    <section className="py-xl px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
      <div className="flex justify-between items-end mb-lg gap-md">
        <div className="space-y-xs">
          <h2 className="font-display-lg text-headline-md text-on-background">Explore with EuroTransit</h2>
          <p className="font-body-md text-body-md text-on-surface-variant">
            The most served destinations on the network right now.
          </p>
        </div>
        <Link to="/search" className="text-primary font-bold flex items-center gap-xs hover:underline shrink-0">
          View all
          <Icon name="arrow_forward" />
        </Link>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-lg">
        {isLoading
          ? Array.from({ length: 6 }, (_, i) => (
              <div key={i} className="rounded-xl overflow-hidden bg-surface-container-lowest shadow-sm">
                <div className="h-48 bg-surface-container shimmer" />
                <div className="p-md space-y-sm">
                  <div className="h-6 w-2/3 bg-surface-container rounded shimmer" />
                  <div className="h-4 w-1/3 bg-surface-container rounded shimmer" />
                </div>
              </div>
            ))
          : destinations.map((d) => <DestinationTile key={d.code} destination={d} />)}
      </div>
    </section>
  )
}

function DestinationTile({ destination: d }: { destination: DestinationSummary }) {
  return (
    <Link
      to={`/search?destination=${encodeURIComponent(d.code)}`}
      className="group block text-left overflow-hidden rounded-xl bg-surface-container-lowest shadow-sm hover:shadow-xl transition-all duration-300 transform hover:-translate-y-1"
    >
      <div className={`relative h-48 overflow-hidden bg-gradient-to-br ${artOf(d.code)}`}>
        <span
          aria-hidden
          className="absolute -bottom-8 -right-2 font-display-lg text-[10rem] leading-none font-bold text-inverse-on-surface/15 select-none group-hover:scale-110 group-hover:text-inverse-on-surface/25 transition-all duration-500 origin-bottom-right"
        >
          {d.name.charAt(0)}
        </span>
        <Icon
          name="train"
          filled
          className="absolute bottom-md left-md text-inverse-on-surface/80 !text-4xl"
        />
        <div className="absolute top-md left-md">
          <Badge tone="primary">
            {d.tripCount} {d.tripCount === 1 ? 'departure' : 'departures'}
          </Badge>
        </div>
      </div>
      <div className="p-md space-y-sm">
        <div className="flex justify-between items-start gap-md">
          <div className="min-w-0">
            <h3 className="font-title-lg text-title-lg text-on-surface truncate">{d.name}</h3>
            <p className="font-body-sm text-body-sm text-on-surface-variant">{d.country}</p>
          </div>
          <div className="text-right shrink-0">
            <p className="text-on-surface-variant font-label-caps text-label-caps">FROM</p>
            <p className="text-primary font-label-tabular text-label-tabular text-xl tabular-nums">
              {formatCents(d.fromPriceCents, d.currency)}
            </p>
          </div>
        </div>
      </div>
    </Link>
  )
}
