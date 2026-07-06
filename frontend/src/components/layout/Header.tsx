import { Link, NavLink } from 'react-router-dom'
import Icon from '../Icon'
import ThemeToggle from '../ui/ThemeToggle'
import { currentUser, isAuthenticated, login, logout } from '../../auth'
import { useGetMeQuery } from '../../features/profile/profileApi'
import CartIndicator from '../../features/orders/CartIndicator'

const navLink = ({ isActive }: { isActive: boolean }) =>
  `font-body-md text-body-md transition-colors duration-200 ${
    isActive ? 'text-primary font-bold' : 'text-on-surface-variant hover:text-primary'
  }`

export default function Header() {
  const authed = isAuthenticated()
  const { data: profile } = useGetMeQuery(undefined, { skip: !authed })
  const displayName = profile?.givenName || profile?.email || currentUser() || 'Account'

  return (
    <header className="w-full top-0 sticky bg-surface/80 backdrop-blur-md shadow-sm z-50">
      <nav className="flex justify-between items-center h-20 px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
        <div className="flex items-center gap-xl">
          <Link to="/" className="font-display-lg text-display-lg font-bold text-primary tracking-tight">
            EuroTransit
          </Link>
          <div className="hidden md:flex items-center gap-lg">
            {authed && (
              <NavLink to="/orders" className={navLink}>
                My Trips
              </NavLink>
            )}
          </div>
        </div>
        <div className="flex items-center gap-md">
          <ThemeToggle />
          <CartIndicator />
          {authed ? (
            <div className="flex items-center gap-sm">
              <Link
                to="/profile"
                className="flex items-center gap-xs px-md py-sm bg-primary-container text-on-primary-container rounded-full font-bold hover:brightness-105 active:scale-95 transition-all"
              >
                <Icon name="account_circle" />
                <span className="hidden sm:inline max-w-[10rem] truncate">{displayName}</span>
              </Link>
              <button
                onClick={logout}
                className="flex items-center justify-center w-9 h-9 rounded-full text-on-surface-variant hover:text-primary hover:bg-surface-container transition-colors"
                aria-label="Sign out"
              >
                <Icon name="logout" />
              </button>
            </div>
          ) : (
            <button
              onClick={login}
              className="flex items-center gap-xs px-md py-sm bg-primary-container text-on-primary-container rounded-full font-bold active:scale-95 transition-all"
            >
              <Icon name="account_circle" />
              <span>Sign In</span>
            </button>
          )}
        </div>
      </nav>
    </header>
  )
}
