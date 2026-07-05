import Hero from './Hero'
import DestinationGrid from './DestinationGrid'
import RailPassBanner from './RailPassBanner'
import TrustBento from './TrustBento'

export default function HomePage() {
  return (
    <main className="flex-grow">
      <Hero />
      <DestinationGrid />
      <RailPassBanner />
      <TrustBento />
    </main>
  )
}
