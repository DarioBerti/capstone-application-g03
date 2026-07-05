import Icon from '../Icon'
import { login } from '../../auth'

// Gate for authenticated-only pages (my bookings, profile). Sign-in is always an explicit action.
export default function SignInRequired({ message }: { message: string }) {
  return (
    <main className="flex-grow w-full max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-xl">
      <div className="flex flex-col items-center justify-center text-center gap-md py-xl">
        <div className="w-16 h-16 rounded-full bg-primary/10 flex items-center justify-center">
          <Icon name="lock" className="text-primary text-3xl" />
        </div>
        <h1 className="font-headline-md text-headline-md">Sign in required</h1>
        <p className="font-body-md text-body-md text-on-surface-variant max-w-md">{message}</p>
        <button
          onClick={login}
          className="mt-sm px-lg py-md bg-primary-container text-on-primary-container rounded-full font-bold active:scale-95 transition-all"
        >
          Sign in with Keycloak
        </button>
      </div>
    </main>
  )
}
