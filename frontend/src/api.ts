import type { Authorization, InventoryItem, Order, Page, Product } from './types'

// All requests go to same-origin /api/* and are proxied to the right service by Vite (vite.config.ts).
async function json<T>(res: Response): Promise<T> {
  const text = await res.text()
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText}${text ? ` — ${text}` : ''}`)
  }
  return (text ? JSON.parse(text) : undefined) as T
}

export interface ProductQuery {
  name?: string
  maxPriceCents?: number
  page?: number
  size?: number
}

export const api = {
  listProducts: (q: ProductQuery = {}): Promise<Page<Product>> => {
    const p = new URLSearchParams()
    if (q.name) p.set('name', q.name)
    if (q.maxPriceCents != null) p.set('maxPriceCents', String(q.maxPriceCents))
    p.set('page', String(q.page ?? 0))
    p.set('size', String(q.size ?? 50))
    return fetch(`/api/catalog/products?${p.toString()}`).then((r) => json<Page<Product>>(r))
  },

  placeOrder: (body: { productId: string; qty: number }): Promise<Order> =>
    fetch('/api/orders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then((r) => json<Order>(r)),

  getOrder: (orderId: string): Promise<Order> =>
    fetch(`/api/orders/${orderId}`).then((r) => json<Order>(r)),

  listInventory: (): Promise<InventoryItem[]> =>
    fetch('/api/inventory/items').then((r) => json<InventoryItem[]>(r)),

  getAuthorization: (orderId: string): Promise<Authorization> =>
    fetch(`/api/payments/authorizations/${orderId}`).then((r) => json<Authorization>(r)),
}
