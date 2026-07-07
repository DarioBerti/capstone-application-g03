// Minimal typing + loader for PayPal's Buttons SDK (PayPal Checkout). Clicking the button opens PayPal's own
// hosted approval flow (redirect/popup) where the buyer approves the order or enters a card — none of it touches
// our bundle. We only ever receive back the approved order id, which we forward to `POST /pay`.

// Subset of PayPal's button style options we use; see PayPal's docs for the full set.
export interface PayPalButtonsStyle {
  layout?: 'vertical' | 'horizontal'
  color?: 'gold' | 'blue' | 'silver' | 'white' | 'black'
  shape?: 'rect' | 'pill'
  label?: 'paypal' | 'checkout' | 'buynow' | 'pay'
  height?: number
}

export interface PayPalButtonsOptions {
  // Return the PayPal order the buyer should approve (created server-side for this booking).
  createOrder: () => Promise<string>
  // Fired once the buyer approves in PayPal's flow; `orderID` is the approved order to authorize.
  onApprove: (data: { orderID: string }) => void | Promise<void>
  onCancel?: () => void
  onError?: (error: unknown) => void
  style?: PayPalButtonsStyle
}

export interface PayPalButtons {
  render(container: string | HTMLElement): Promise<void>
  close(): Promise<void>
}

export interface PayPalNamespace {
  Buttons(options: PayPalButtonsOptions): PayPalButtons
}

declare global {
  interface Window {
    paypal?: PayPalNamespace
  }
}

const SDK_ORIGIN = 'https://www.paypal.com'

// The SDK is a singleton on the page; load it once and reuse. A failed load clears the cache so a later retry
// can re-attempt.
let sdkPromise: Promise<PayPalNamespace> | null = null

export function loadPayPalSdk(clientId: string): Promise<PayPalNamespace> {
  if (window.paypal) return Promise.resolve(window.paypal)
  if (sdkPromise) return sdkPromise

  sdkPromise = new Promise<PayPalNamespace>((resolve, reject) => {
    const params = new URLSearchParams({
      'client-id': clientId,
      components: 'buttons',
      intent: 'authorize',
      currency: 'EUR',
    })
    const script = document.createElement('script')
    script.src = `${SDK_ORIGIN}/sdk/js?${params.toString()}`
    script.onload = () =>
      window.paypal
        ? resolve(window.paypal)
        : reject(new Error('PayPal SDK loaded but window.paypal is unavailable'))
    script.onerror = () => {
      sdkPromise = null
      reject(new Error('Failed to load the PayPal SDK'))
    }
    document.head.appendChild(script)
  })
  return sdkPromise
}
