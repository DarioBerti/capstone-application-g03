import Icon from '../../components/Icon'

// Marketing bento shown under the results (static — no domain data).
export default function InfoBento() {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-md">
      <div className="bg-primary text-on-primary rounded-xl p-lg flex flex-col justify-between min-h-[180px]">
        <div>
          <Icon name="eco" className="text-primary-fixed text-4xl mb-md" />
          <h3 className="font-headline-md text-headline-md leading-tight">Travel sustainably with EuroTransit</h3>
        </div>
        <p className="font-body-sm text-body-sm opacity-90">
          Rail travel produces up to 90% less CO₂ than flying between major European cities.
        </p>
      </div>
      <div className="bg-surface-container-high rounded-xl p-lg flex flex-col justify-between min-h-[180px]">
        <div className="flex justify-between items-start">
          <h3 className="font-title-lg text-title-lg text-on-surface max-w-[180px]">Manage your booking anywhere</h3>
          <Icon name="app_shortcut" className="text-primary" />
        </div>
        <div className="flex gap-sm">
          <div className="flex-grow h-12 bg-surface-container-lowest rounded-lg border border-outline-variant" />
          <div className="flex-grow h-12 bg-surface-container-lowest rounded-lg border border-outline-variant" />
        </div>
      </div>
    </div>
  )
}
