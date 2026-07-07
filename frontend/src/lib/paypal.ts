// Minimal typing + loader for PayPal's Card-Fields SDK (Advanced Card Payments). The card inputs render inside
// PayPal-owned iframes, so the PAN/CVV never touch our bundle — we only ever receive the approved order id.

export interface PayPalCardField {
  render(container: string | HTMLElement): Promise<void>
}

export interface PayPalCardFields {
  isEligible(): boolean
  NameField(): PayPalCardField
  NumberField(): PayPalCardField
  ExpiryField(): PayPalCardField
  CVVField(): PayPalCardField
  submit(): Promise<void>
}

export interface PayPalCardFieldsOptions {
  // Return the PayPal order the browser should approve (created server-side for this booking).
  createOrder: () => Promise<string>
  // Fired once PayPal has attached the card + run 3DS; `orderID` is the approved order to authorize.
  onApprove: (data: { orderID: string }) => void
  onError: (error: unknown) => void
}

export interface PayPalNamespace {
  CardFields(options: PayPalCardFieldsOptions): PayPalCardFields
}

declare global {
  interface Window {
    paypal?: PayPalNamespace
  }
}

const SDK_ORIGIN = 'https://www.paypal.com'

// The SDK is a singleton on the page; load it once (with the session's client token) and reuse. A failed load
// clears the cache so a later retry can re-attempt.
let sdkPromise: Promise<PayPalNamespace> | null = null

export function loadPayPalSdk(clientId: string, clientToken: string): Promise<PayPalNamespace> {
  if (window.paypal) return Promise.resolve(window.paypal)
  if (sdkPromise) return sdkPromise

  sdkPromise = new Promise<PayPalNamespace>((resolve, reject) => {
    const params = new URLSearchParams({
      'client-id': clientId,
      components: 'card-fields',
      intent: 'authorize',
      currency: 'EUR',
    })
    const script = document.createElement('script')
    script.src = `${SDK_ORIGIN}/sdk/js?${params.toString()}`
    script.setAttribute('data-client-token', clientToken)
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
