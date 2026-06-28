import { useEffect, useState } from 'react'
import CatalogPanel from './components/CatalogPanel'
import OrderForm from './components/OrderForm'
import SagaTimeline from './components/SagaTimeline'
import { api } from './api'
import { currentUser, isAuthenticated, login, logout } from './auth'
import type { Profile } from './types'

export default function App() {
  const [productId, setProductId] = useState('')
  const [activeOrderId, setActiveOrderId] = useState<string | null>(null)
  const authed = isAuthenticated()
  const [profile, setProfile] = useState<Profile | null>(null)

  // Once signed in, fetch (and just-in-time provision) the caller's profile from the users service.
  useEffect(() => {
    if (!authed) return
    api.getMyProfile().then(setProfile).catch(() => setProfile(null))
  }, [authed])

  return (
    <div className="app">
      <header className="app-head">
        <div className="app-head-row">
          <h1>EuroTransit · Debug Console</h1>
          <div className="auth-box">
            {authed ? (
              <>
                <span className="muted">{profile?.email ?? currentUser() ?? 'signed in'}</span>
                <button onClick={logout}>Sign out</button>
              </>
            ) : (
              <button onClick={login}>Sign in</button>
            )}
          </div>
        </div>
        <p className="muted">
          Drives the place-order saga across <b>catalog → orders → inventory → payments</b>. Run the
          services locally and <code>just dev</code> here (Vite proxies to them).
        </p>
      </header>

      {authed ? (
        <main className="grid">
          <CatalogPanel onSelect={setProductId} />
          <OrderForm productId={productId} onProductId={setProductId} onPlaced={setActiveOrderId} />
          <SagaTimeline orderId={activeOrderId} />
        </main>
      ) : (
        <main className="grid">
          <p className="muted">
            Sign in with Keycloak to browse the catalog and drive the saga — every API call now carries
            your bearer token.
          </p>
        </main>
      )}
    </div>
  )
}
