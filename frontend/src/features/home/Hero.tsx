import SearchForm from './SearchForm'

// Stitch placeholder hero image (may expire) — layered over an inverse-surface gradient so the section still
// reads well if the remote asset fails to load.
const HERO_IMAGE =
  'https://lh3.googleusercontent.com/aida-public/AB6AXuBQGRmDS0kwRSQ1pNNlYS6_v7_n71LeFtShnQuzWjKA6DMllGFCwOX1C54Pxz_BzXQO8oJRy_DjOIxMONqlsEqMFmDXTUERTSyeSWjxYgnsH_fIE9KtbVcCqLTipgGcSqcNtOipBaLisSNlTQpw_wFk6P02uUGGn192HZ4FufvQQy3mAK0LO68sQhjq8Htp9tm-PS-YjQO8wo_AECsvqrt7kx9Aov8e-E7EolH4HItHKhXcBPZV6ZCYyw'

export default function Hero() {
  return (
    <section className="relative min-h-[680px] md:min-h-[780px] w-full flex items-center bg-inverse-surface">
      <div className="absolute inset-0 z-0">
        <img
          src={HERO_IMAGE}
          alt="A high-speed train speeding through the rolling green hills of the Alps at golden hour"
          className="w-full h-full object-cover"
          onError={(e) => {
            e.currentTarget.style.display = 'none'
          }}
        />
        <div className="absolute inset-0 bg-gradient-to-t from-on-background/70 via-on-background/25 to-on-background/10" />
      </div>
      <div className="relative z-10 w-full max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop">
        <div className="max-w-3xl space-y-md">
          <h1 className="font-display-lg text-display-lg-mobile md:text-display-lg text-white drop-shadow-lg">
            Europe, interconnected <br className="hidden md:block" />
            by speed and style.
          </h1>
          <p className="font-body-md text-body-md text-white/90 max-w-xl drop-shadow-md">
            Experience the continent's most beautiful landscapes from the comfort of a high-speed cabin.
            Book your cross-border journey in seconds.
          </p>
        </div>
        <SearchForm />
      </div>
    </section>
  )
}
