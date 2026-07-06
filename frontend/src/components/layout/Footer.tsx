export default function Footer() {
  return (
    <footer className="w-full mt-auto bg-inverse-surface">
      <div className="space-y-md py-xl px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
        <div className="font-headline-md text-headline-md text-inverse-on-surface">EuroTransit</div>
        <p className="font-body-sm text-body-sm text-inverse-on-surface/70 max-w-xs">
          The premium standard for European rail travel. Connecting cities, cultures, and people with
          high-speed precision.
        </p>
      </div>
      <div className="max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-md border-t border-inverse-on-surface/10">
        <p className="font-body-sm text-body-sm text-inverse-on-surface/50">
          © {new Date().getFullYear()} EuroTransit. All rights reserved.
        </p>
      </div>
    </footer>
  )
}
