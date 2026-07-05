import { useState, type FormEvent } from 'react'
import Icon from '../../components/Icon'
import { formatCents } from '../../lib/format'
import type { Order } from '../../types'

// IMPORTANT: this card form is COSMETIC. The real backend `POST /api/orders/{id}/pay` is a one-click trigger
// with NO card data — the buyer is taken from the JWT and the PSP (PayPal sandbox) authorizes server-to-server
// inside the payments service. The SPA must hold no PSP data (hard invariant), so we validate the fields only
// for the look and NEVER transmit them: submit simply calls onPay().
export default function PaymentForm({
  order,
  onPay,
  processing,
}: {
  order: Order
  onPay: () => void
  processing: boolean
}) {
  const [name, setName] = useState('')
  const [number, setNumber] = useState('')
  const [expiry, setExpiry] = useState('')
  const [cvv, setCvv] = useState('')

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    onPay() // card fields are intentionally NOT read/sent
  }

  const field = 'w-full p-md bg-surface-container-low border border-outline-variant rounded-lg focus:ring-2 focus:ring-primary focus:outline-none transition-all'
  const label = 'font-label-caps text-label-caps text-on-surface-variant'

  return (
    <div className="bg-surface-container-lowest p-xl rounded-xl shadow-sm border border-outline-variant/10">
      <h1 className="font-headline-md text-headline-md text-on-surface mb-lg">Payment Details</h1>
      <div className="mb-lg flex items-start gap-sm p-md bg-secondary-container/30 rounded-lg text-on-secondary-container">
        <Icon name="info" className="text-primary shrink-0" />
        <p className="font-body-sm text-body-sm">
          Sandbox demo — card details are not collected or sent. Payment is authorized securely by our provider.
        </p>
      </div>
      <form className="flex flex-col gap-lg" onSubmit={onSubmit}>
        <div className="flex flex-col gap-sm">
          <label className={label} htmlFor="cardholder">CARDHOLDER NAME</label>
          <input id="cardholder" className={field} placeholder="Johnathan Doe" value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" />
        </div>
        <div className="flex flex-col gap-sm">
          <label className={label} htmlFor="cardnumber">CARD NUMBER</label>
          <div className="relative">
            <Icon name="credit_card" className="absolute left-4 top-1/2 -translate-y-1/2 text-primary z-10" />
            <input id="cardnumber" className={`${field} pl-12`} placeholder="•••• •••• •••• 4421" value={number} onChange={(e) => setNumber(e.target.value)} autoComplete="off" inputMode="numeric" />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-md">
          <div className="flex flex-col gap-sm">
            <label className={label} htmlFor="expiry">EXPIRY DATE</label>
            <input id="expiry" className={field} placeholder="MM/YY" value={expiry} onChange={(e) => setExpiry(e.target.value)} autoComplete="off" />
          </div>
          <div className="flex flex-col gap-sm">
            <label className={label} htmlFor="cvv">CVV</label>
            <input id="cvv" className={field} placeholder="•••" value={cvv} onChange={(e) => setCvv(e.target.value)} autoComplete="off" inputMode="numeric" />
          </div>
        </div>
        <button
          type="submit"
          disabled={processing}
          className="w-full py-lg bg-accent text-on-background rounded-xl font-bold text-lg hover:brightness-110 active:scale-[0.98] transition-all shadow-md flex items-center justify-center gap-md overflow-hidden relative group disabled:opacity-60 disabled:pointer-events-none"
        >
          <div className="shimmer absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity" />
          {processing ? (
            <>
              <Icon name="progress_activity" className="animate-spin relative z-10" />
              <span className="relative z-10">Processing Payment…</span>
            </>
          ) : (
            <span className="relative z-10">Pay {formatCents(order.amountCents, order.currency)}</span>
          )}
        </button>
      </form>
      <div className="mt-lg flex items-center justify-center gap-lg text-on-surface-variant/50">
        <Icon name="verified_user" />
        <Icon name="lock" />
        <Icon name="payments" />
      </div>
    </div>
  )
}
