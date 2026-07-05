import { Link, useNavigate } from 'react-router-dom'
import Icon from '../../components/Icon'
import Badge from '../../components/ui/Badge'

interface Destination {
  city: string
  fromCents: number
  blurb: string
  tag: string
  tone: 'primary' | 'tertiary'
  image: string
}

// Marketing "popular destinations" (static). Clicking searches trips to that city. Images are Stitch
// placeholders with a gradient fallback if the remote asset fails.
const DESTINATIONS: Destination[] = [
  {
    city: 'London',
    fromCents: 4900,
    blurb: 'Discover the historic charm and modern energy of the UK capital, a fast ride via the Channel Tunnel.',
    tag: 'DIRECT TRAIN',
    tone: 'primary',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuCZreq6oDQqOac0Vpsyr9cXqDhxyy4zJU1HVV8-PkkcpiXIMVNapdBsfeUQScqJFrgmGQEOOx2WPF24WjjR57fc2If8HREbvrwebz5eDQrwmaChcptMMW6aa-9YMDRvtJdYpExeY_AnC99CzD91LI25fEaZxDT7cSOb0hlWICCQ8pddRXhddghvEltW67Jw97N-2uYq2GkWWJCMWlOF02Zpg0Url2rzZPUo7-8E7yDwBBlxGyke5lYiEQ',
  },
  {
    city: 'Paris',
    fromCents: 3500,
    blurb: 'The City of Light awaits with world-class dining, fashion, and the timeless Eiffel Tower.',
    tag: 'POPULAR',
    tone: 'primary',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuBqd7hjLdFrYZddzjb5L8yTxEBikUeK8w9gtDmOjEwvaKNOp8FBOK3p5p6mWQRMtmJMm3hqpEHTgut47qVtreOIXBOsxs9IvYx2llESyy69mKWZaYkC_AFYssujhuuehJUw6ckvxXatdowjdV9-5F05S-ZT_3nC1-aUH38195-2gPuD8lHP3dl-Lj54tGKD0t7NVTULbr1rnuGALeL9_ZxOa9ky_PYiZSnMbo-nNd-XX1bjGApvolxX8Q',
  },
  {
    city: 'Milan',
    fromCents: 6200,
    blurb: 'Experience the fashion capital of Italy, with stunning architecture and a gateway to the Lakes.',
    tag: 'LAST MINUTE',
    tone: 'tertiary',
    image:
      'https://lh3.googleusercontent.com/aida-public/AB6AXuBksLVJ-4KSTmZ5HsOrixBmFHNobOJcAAn85_KX1DRAQMgdn03Xm05woiYaxNs2w5V6xa3u-QHhoBEzk0lNFoR6Ufi940MWo05dufSaoz9csjQpRxo5EW-AXg8Sk7l55j6pu9PFtTD-JsB9sGd0cW2xk_uAEHyOTNx1uGlD0APsE1CjCJ2vDBEmkmFa7IF3Y56iVFXeNAH3KRUW52mwe6H3hBL4yXPTi7740JfFahbeGqDTB7tqmocq4A',
  },
]

function euros(cents: number) {
  return `€${(cents / 100).toFixed(2)}`
}

export default function DestinationGrid() {
  const navigate = useNavigate()
  return (
    <section className="py-xl px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
      <div className="flex justify-between items-end mb-lg gap-md">
        <div className="space-y-xs">
          <h2 className="font-display-lg text-headline-md text-on-background">Explore with EuroTransit</h2>
          <p className="font-body-md text-body-md text-on-surface-variant">
            Handpicked destinations for your next European escape.
          </p>
        </div>
        <Link to="/search" className="text-primary font-bold flex items-center gap-xs hover:underline shrink-0">
          View all
          <Icon name="arrow_forward" />
        </Link>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-lg">
        {DESTINATIONS.map((d) => (
          <button
            key={d.city}
            onClick={() => navigate(`/search?destination=${encodeURIComponent(d.city)}`)}
            className="group text-left overflow-hidden rounded-xl bg-surface-container-lowest shadow-sm hover:shadow-xl transition-all duration-300 transform hover:-translate-y-1"
          >
            <div className="relative h-64 overflow-hidden bg-gradient-to-br from-primary-container to-inverse-surface">
              <img
                src={d.image}
                alt={`${d.city} cityscape`}
                className="w-full h-full object-cover group-hover:scale-110 transition-transform duration-500"
                onError={(e) => {
                  e.currentTarget.style.display = 'none'
                }}
              />
              <div className="absolute top-md left-md">
                <Badge tone={d.tone}>{d.tag}</Badge>
              </div>
            </div>
            <div className="p-md space-y-sm">
              <div className="flex justify-between items-start">
                <h3 className="font-title-lg text-title-lg">{d.city}</h3>
                <div className="text-right">
                  <p className="text-on-surface-variant font-label-caps text-label-caps">FROM</p>
                  <p className="text-primary font-label-tabular text-label-tabular text-xl tabular-nums">{euros(d.fromCents)}</p>
                </div>
              </div>
              <p className="font-body-sm text-body-sm text-on-surface-variant line-clamp-2">{d.blurb}</p>
            </div>
          </button>
        ))}
      </div>
    </section>
  )
}
