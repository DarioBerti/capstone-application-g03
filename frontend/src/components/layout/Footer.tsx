import Icon from '../Icon'

const linkClass = 'block text-on-secondary-container/70 hover:text-primary-fixed transition-colors font-body-sm text-body-sm'

export default function Footer() {
  return (
    <footer className="w-full mt-auto bg-inverse-surface dark:bg-surface-container-lowest border-t border-outline-variant">
      <div className="grid grid-cols-1 md:grid-cols-4 gap-gutter py-xl px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
        <div className="space-y-md">
          <div className="font-headline-md text-headline-md text-surface-bright">EuroTransit</div>
          <p className="font-body-sm text-body-sm text-on-secondary-container/70 max-w-xs">
            The premium standard for European rail travel. Connecting cities, cultures, and people with
            high-speed precision.
          </p>
          <div className="flex gap-sm">
            <a className="w-8 h-8 rounded-full bg-white/10 flex items-center justify-center hover:bg-primary transition-colors" href="#" aria-label="Share">
              <Icon name="share" className="text-sm" />
            </a>
            <a className="w-8 h-8 rounded-full bg-white/10 flex items-center justify-center hover:bg-primary transition-colors" href="#" aria-label="Email">
              <Icon name="mail" className="text-sm" />
            </a>
          </div>
        </div>
        <div className="space-y-md">
          <h5 className="text-surface-bright font-bold">Company</h5>
          <div className="space-y-sm">
            <a className={linkClass} href="#">Press Kit</a>
            <a className={linkClass} href="#">Sustainability</a>
            <a className={linkClass} href="#">Contact Us</a>
          </div>
        </div>
        <div className="space-y-md">
          <h5 className="text-surface-bright font-bold">Legal</h5>
          <div className="space-y-sm">
            <a className={linkClass} href="#">Privacy Policy</a>
            <a className={linkClass} href="#">Terms of Service</a>
            <a className={linkClass} href="#">Cookie Settings</a>
          </div>
        </div>
        <div className="space-y-md">
          <h5 className="text-surface-bright font-bold">Newsletter</h5>
          <p className="font-body-sm text-body-sm text-on-secondary-container/70">
            Subscribe for travel inspiration and exclusive deals.
          </p>
          <form className="flex gap-xs" onSubmit={(e) => e.preventDefault()}>
            <input
              className="bg-white/10 border border-white/20 rounded px-sm py-xs w-full text-white text-sm placeholder:text-white/50 focus:outline-none focus:ring-2 focus:ring-primary"
              placeholder="Email"
              type="email"
              aria-label="Email address"
            />
            <button className="bg-primary text-white px-md py-xs rounded hover:brightness-110" type="submit">
              Join
            </button>
          </form>
        </div>
      </div>
      <div className="max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-md border-t border-white/10">
        <p className="font-body-sm text-body-sm text-on-secondary-container/50">
          © 2024 EuroTransit Rail S.A. All rights reserved.
        </p>
      </div>
    </footer>
  )
}
