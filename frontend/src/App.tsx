import { useState } from 'react'
import CatalogPanel from './components/CatalogPanel'
import OrderForm from './components/OrderForm'
import SagaTimeline from './components/SagaTimeline'

export default function App() {
  const [productId, setProductId] = useState('')
  const [activeOrderId, setActiveOrderId] = useState<string | null>(null)

  return (
    <div className="app">
      <header className="app-head">
        <h1>EuroTransit · Debug Console</h1>
        <p className="muted">
          Drives the place-order saga across <b>catalog → orders → inventory → payments</b>. Run the
          services locally and <code>just dev</code> here (Vite proxies to them).
        </p>
      </header>

      <main className="grid">
        <CatalogPanel onSelect={setProductId} />
        <OrderForm productId={productId} onProductId={setProductId} onPlaced={setActiveOrderId} />
        <SagaTimeline orderId={activeOrderId} />
      </main>
    </div>
  )
}
