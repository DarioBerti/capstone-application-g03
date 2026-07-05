import { Link } from 'react-router-dom'
import Icon from '../components/Icon'

export default function NotFoundPage() {
  return (
    <main className="flex-grow w-full max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-xl">
      <div className="flex flex-col items-center justify-center text-center gap-md py-xl">
        <Icon name="wrong_location" className="text-5xl text-outline" />
        <h1 className="font-display-lg text-display-lg-mobile">Page not found</h1>
        <p className="font-body-md text-body-md text-on-surface-variant">This journey doesn't exist on our network.</p>
        <Link to="/" className="mt-sm text-primary font-bold hover:underline">
          Back to home
        </Link>
      </div>
    </main>
  )
}
