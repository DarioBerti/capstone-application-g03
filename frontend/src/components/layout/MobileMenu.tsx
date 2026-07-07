import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { NavLink, useLocation } from 'react-router-dom'
import Icon from '../Icon'
import ThemeToggle from '../ui/ThemeToggle'
import { login, logout } from '../../auth'
import { iconButton, navLink } from '../ui/classRecipes'

export default function MobileMenu({ authed, displayName }: { authed: boolean; displayName: string }) {
  const [open, setOpen] = useState(false)
  const wasOpen = useRef(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const { pathname } = useLocation()

  useEffect(() => {
    setOpen(false)
  }, [pathname])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  useEffect(() => {
    if (!open) return
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = ''
    }
  }, [open])

  // md:hidden makes the open drawer invisible past 768px but would leave the scroll lock behind;
  // close it for real when the viewport grows across the breakpoint.
  useEffect(() => {
    if (!open) return
    const mq = window.matchMedia('(min-width: 768px)')
    const onChange = () => {
      if (mq.matches) setOpen(false)
    }
    onChange()
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [open])

  useEffect(() => {
    if (open) panelRef.current?.focus()
    else if (wasOpen.current) triggerRef.current?.focus()
    wasOpen.current = open
  }, [open])

  return (
    <div className="md:hidden">
      <button
        ref={triggerRef}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-controls="mobile-menu"
        aria-label="Open menu"
        className={iconButton}
      >
        <Icon name="menu" />
      </button>
      {/* Portaled to <body>: the header's backdrop-blur makes it the containing block for fixed
          descendants, which would clip the drawer to the bar. md:hidden must be repeated here because
          the portal escapes the wrapper above. */}
      {open &&
        createPortal(
          <>
            <div
              aria-hidden
              data-testid="mobile-menu-backdrop"
              onClick={() => setOpen(false)}
              className="fixed inset-0 z-[60] bg-on-background/40 md:hidden"
            />
            <div
              id="mobile-menu"
              role="dialog"
              aria-modal="true"
              aria-label="Menu"
              ref={panelRef}
              tabIndex={-1}
              className="fixed inset-y-0 right-0 z-[70] w-72 max-w-[85vw] bg-surface shadow-2xl border-l border-outline-variant p-lg flex flex-col gap-lg focus:outline-none md:hidden"
            >
              <div className="flex items-center justify-between">
                <span className="font-label-caps text-label-caps text-on-surface-variant">MENU</span>
                <button onClick={() => setOpen(false)} aria-label="Close menu" className={iconButton}>
                  <Icon name="close" />
                </button>
              </div>
              {authed && (
                <nav className="flex flex-col gap-md">
                  <NavLink to="/orders" className={navLink} onClick={() => setOpen(false)}>
                    My Trips
                  </NavLink>
                  <NavLink to="/profile" className={navLink} onClick={() => setOpen(false)}>
                    Profile — {displayName}
                  </NavLink>
                </nav>
              )}
              <div className="flex items-center justify-between border-t border-outline-variant pt-md">
                <span className="font-body-md text-body-md text-on-surface-variant">Theme</span>
                <ThemeToggle />
              </div>
              <div className="mt-auto">
                {authed ? (
                  <button
                    onClick={logout}
                    className="w-full flex items-center justify-center gap-xs px-md py-sm bg-surface-container text-on-surface rounded-full font-bold hover:bg-surface-container-high transition-colors"
                  >
                    <Icon name="logout" />
                    Sign out
                  </button>
                ) : (
                  <button
                    onClick={login}
                    className="w-full flex items-center justify-center gap-xs px-md py-sm bg-primary-container text-on-primary-container rounded-full font-bold active:scale-95 transition-all"
                  >
                    <Icon name="account_circle" />
                    Sign In
                  </button>
                )}
              </div>
            </div>
          </>,
          document.body,
        )}
    </div>
  )
}
