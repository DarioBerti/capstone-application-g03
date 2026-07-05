import Icon from '../../components/Icon'

export default function RailPassBanner() {
  return (
    <section className="py-xl px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
      <div className="relative overflow-hidden rounded-2xl bg-inverse-surface text-white p-xl flex flex-col md:flex-row items-center gap-xl border border-outline-variant/20">
        <div className="relative z-10 flex-1 space-y-md">
          <span className="inline-block font-label-caps text-label-caps bg-primary/20 text-primary-fixed border border-primary/30 px-sm py-xs rounded">
            SPECIAL OFFER
          </span>
          <h2 className="font-display-lg text-headline-md">The Whole Continent, One Rail Pass.</h2>
          <p className="font-body-md text-body-md text-white/80 max-w-lg">
            Get the EuroTransit Global Pass and enjoy unlimited travel across 33 countries. Flexible,
            sustainable, and entirely digital. Save up to 25% for youth and seniors.
          </p>
          <div className="flex flex-wrap gap-md pt-sm">
            <button className="bg-accent text-on-background px-lg py-md rounded-lg font-bold hover:brightness-110 transition-all shadow-lg active:scale-95">
              Explore Rail Passes
            </button>
            <button className="border border-white/30 px-lg py-md rounded-lg font-bold hover:bg-white/10 transition-all">
              How it Works
            </button>
          </div>
        </div>
        <div className="relative z-10 w-full md:w-1/3 aspect-video md:aspect-square overflow-hidden rounded-xl bg-surface-container-highest/10 flex items-center justify-center">
          <Icon name="qr_code_2" className="text-[96px] text-primary-fixed/80" />
        </div>
        <div className="absolute -right-20 -top-20 w-96 h-96 bg-primary opacity-10 rounded-full blur-3xl" />
        <div className="absolute -left-20 -bottom-20 w-96 h-96 bg-accent opacity-5 rounded-full blur-3xl" />
      </div>
    </section>
  )
}
