import { Link, NavLink } from 'react-router-dom'
import Icon from '../Icon'
import ThemeToggle from '../ui/ThemeToggle'
import { currentUser, isAuthenticated, login, logout } from '../../auth'
import { useGetMeQuery } from '../../features/profile/profileApi'
import CartIndicator from '../../features/orders/CartIndicator'
import MobileMenu from './MobileMenu'
import { iconButton, navLink } from '../ui/classRecipes'

export default function Header() {
  const authed = isAuthenticated()
  const { data: profile } = useGetMeQuery(undefined, { skip: !authed })
  const displayName = profile?.givenName || profile?.email || currentUser() || 'Account'

  return (
    <header className="w-full top-0 sticky bg-surface/80 backdrop-blur-md shadow-sm z-50">
      <nav className="flex justify-between items-center h-20 px-margin-mobile md:px-margin-desktop max-w-max-width mx-auto">
        <div className="flex items-center gap-xl">
          <Link
            to="/"
            className="font-display-lg text-headline-md sm:text-display-lg-mobile md:text-display-lg font-bold text-primary tracking-tight"
          >
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
        <div className="flex items-center gap-sm md:gap-md">
          <div className="hidden md:block">
            <ThemeToggle />
          </div>
          <CartIndicator />
          {authed ? (
            <div className="hidden md:flex items-center gap-sm">
              <Link
                to="/profile"
                className="flex items-center gap-xs px-md py-sm bg-primary-container text-on-primary-container rounded-full font-bold hover:brightness-105 active:scale-95 transition-all"
              >
                <Icon name="account_circle" />
                <span className="hidden sm:inline max-w-[10rem] truncate">{displayName}</span>
              </Link>
              <button onClick={logout} className={iconButton} aria-label="Sign out">
                <Icon name="logout" />
              </button>
            </div>
          ) : (
            <button
              onClick={login}
              className="hidden md:flex items-center gap-xs px-md py-sm bg-primary-container text-on-primary-container rounded-full font-bold active:scale-95 transition-all"
            >
              <Icon name="account_circle" />
              <span>Sign In</span>
            </button>
          )}
          <MobileMenu authed={authed} displayName={displayName} />
        </div>
      </nav>
    </header>
  )
}
