import Icon from '../../components/Icon'

export default function TrustBento() {
  return (
    <section className="py-xl px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
      <div className="grid grid-cols-1 md:grid-cols-4 gap-gutter">
        <div className="md:col-span-2 bg-surface-container p-lg rounded-xl flex items-start gap-md">
          <div className="w-12 h-12 rounded-full bg-primary/10 flex items-center justify-center shrink-0">
            <Icon name="eco" filled className="text-primary" />
          </div>
          <div>
            <h4 className="font-title-lg text-title-lg mb-xs">Travel Greener</h4>
            <p className="font-body-sm text-body-sm text-on-surface-variant">
              Rail travel emits up to 90% less CO₂ than flying. Join the movement towards sustainable exploration.
            </p>
          </div>
        </div>
        <div className="bg-surface-container-low p-lg rounded-xl flex flex-col justify-between gap-md">
          <Icon name="lock" className="text-primary text-3xl" />
          <div>
            <h4 className="font-title-lg text-title-lg mb-xs">Secure Booking</h4>
            <p className="font-body-sm text-body-sm text-on-surface-variant">Payments are 100% encrypted and secure.</p>
          </div>
        </div>
        <div className="bg-surface-container-low p-lg rounded-xl flex flex-col justify-between gap-md">
          <Icon name="headset_mic" className="text-primary text-3xl" />
          <div>
            <h4 className="font-title-lg text-title-lg mb-xs">24/7 Concierge</h4>
            <p className="font-body-sm text-body-sm text-on-surface-variant">Global support in over 10 languages.</p>
          </div>
        </div>
      </div>
    </section>
  )
}
