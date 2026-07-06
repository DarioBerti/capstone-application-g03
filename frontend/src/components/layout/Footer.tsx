import Icon from '../Icon'

const linkClass = 'block text-inverse-on-surface/70 hover:text-primary-fixed transition-colors font-body-sm text-body-sm'

export default function Footer() {
  return (
    <footer className="w-full mt-auto bg-inverse-surface">
      <div className="grid grid-cols-1 md:grid-cols-4 gap-gutter py-xl px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
        <div className="space-y-md">
          <div className="font-headline-md text-headline-md text-inverse-on-surface">EuroTransit</div>
          <p className="font-body-sm text-body-sm text-inverse-on-surface/70 max-w-xs">
            The premium standard for European rail travel. Connecting cities, cultures, and people with
            high-speed precision.
          </p>
          <div className="flex gap-sm">
            <a
              className="w-8 h-8 rounded-full bg-inverse-on-surface/10 text-inverse-on-surface flex items-center justify-center hover:bg-primary-container hover:text-on-primary-container transition-colors"
              href="#"
              aria-label="Share"
            >
              <Icon name="share" className="text-sm" />
            </a>
            <a
              className="w-8 h-8 rounded-full bg-inverse-on-surface/10 text-inverse-on-surface flex items-center justify-center hover:bg-primary-container hover:text-on-primary-container transition-colors"
              href="#"
              aria-label="Email"
            >
              <Icon name="mail" className="text-sm" />
            </a>
          </div>
        </div>
        <div className="space-y-md">
          <h5 className="text-inverse-on-surface font-bold">Company</h5>
          <div className="space-y-sm">
            <a className={linkClass} href="#">Press Kit</a>
            <a className={linkClass} href="#">Sustainability</a>
            <a className={linkClass} href="#">Contact Us</a>
          </div>
        </div>
        <div className="space-y-md">
          <h5 className="text-inverse-on-surface font-bold">Legal</h5>
          <div className="space-y-sm">
            <a className={linkClass} href="#">Privacy Policy</a>
            <a className={linkClass} href="#">Terms of Service</a>
            <a className={linkClass} href="#">Cookie Settings</a>
          </div>
        </div>
        <div className="space-y-md">
          <h5 className="text-inverse-on-surface font-bold">Newsletter</h5>
          <p className="font-body-sm text-body-sm text-inverse-on-surface/70">
            Subscribe for travel inspiration and exclusive deals.
          </p>
          <form className="flex gap-xs" onSubmit={(e) => e.preventDefault()}>
            <input
              className="bg-inverse-on-surface/10 border border-inverse-on-surface/20 rounded px-sm py-xs w-full text-inverse-on-surface text-sm placeholder:text-inverse-on-surface/50 focus:outline-none focus:ring-2 focus:ring-primary-fixed-dim"
              placeholder="Email"
              type="email"
              name="email"
              autoComplete="email"
              spellCheck={false}
              aria-label="Email address"
            />
            <button
              className="bg-primary-fixed-dim text-on-primary-fixed px-md py-xs rounded font-bold hover:brightness-110 transition-all"
              type="submit"
            >
              Join
            </button>
          </form>
        </div>
      </div>
      <div className="max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-md border-t border-inverse-on-surface/10">
        <p className="font-body-sm text-body-sm text-inverse-on-surface/50">
          © 2024 EuroTransit Rail S.A. All rights reserved.
        </p>
      </div>
    </footer>
  )
}
