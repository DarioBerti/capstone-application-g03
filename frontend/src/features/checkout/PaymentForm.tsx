import { useEffect, useRef, useState } from 'react'
import Icon from '../../components/Icon'
import { describeError } from '../../components/ui/QueryStates'
import { formatCents } from '../../lib/format'
import { loadPayPalSdk, type PayPalButtons } from '../../lib/paypal'
import { useCreatePayPalSessionMutation } from './paymentSessionApi'
import type { Order } from '../../types'

/**
 * Payment via PayPal Checkout. On mount we open a session (Orders → Payments creates a PayPal order for this
 * booking), load PayPal's SDK, and render its Button. Clicking it opens PayPal's own hosted approval flow
 * (redirect/popup) where the buyer approves the order — the card/credentials are entered on PayPal, never in our
 * bundle. `onApprove` hands us the approved order id, which we forward to `onPay` (→ POST /pay).
 */
export default function PaymentForm({
  order,
  onPay,
  processing,
}: {
  order: Order
  onPay: (payPalOrderId: string) => void
  processing: boolean
}) {
  const [createSession] = useCreatePayPalSessionMutation()
  const containerRef = useRef<HTMLDivElement>(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    let cancelled = false
    let buttons: PayPalButtons | null = null
    setReady(false)
    setError(null)
    ;(async () => {
      try {
        const session = await createSession(order.orderId).unwrap()
        const paypal = await loadPayPalSdk(session.clientId)
        if (cancelled || !containerRef.current) return
        const dark = document.documentElement.classList.contains('dark')
        buttons = paypal.Buttons({
          createOrder: async () => session.payPalOrderId,
          onApprove: ({ orderID }) => onPay(orderID),
          onError: (err) => setError(err),
          style: { layout: 'vertical', color: dark ? 'white' : 'black', shape: 'rect', label: 'pay', height: 52 },
        })
        await buttons.render(containerRef.current)
        if (cancelled) return
        setReady(true)
      } catch (err) {
        if (!cancelled) setError(err)
      }
    })()
    return () => {
      cancelled = true
      buttons?.close().catch(() => {})
    }
    // Re-open only if the booking changes; onPay/createSession are stable enough for a single checkout.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order.orderId])

  return (
    <div className="bg-surface-container-lowest p-xl rounded-xl shadow-sm border border-outline-variant/10">
      <h1 className="font-headline-md text-headline-md text-on-surface mb-lg">Payment Details</h1>
      <div className="mb-lg flex items-start gap-sm p-md bg-secondary-container/30 rounded-lg text-on-secondary-container">
        <Icon name="lock" className="text-primary shrink-0" />
        <p className="font-body-sm text-body-sm">
          You'll approve this payment securely on PayPal — your card never touches EuroTransit's servers.
        </p>
      </div>

      <div className="mb-lg flex items-baseline justify-between">
        <span className="font-body-md text-body-md text-on-surface-variant">Total due</span>
        <span className="font-headline-md text-headline-md text-on-surface tabular-nums">
          {formatCents(order.amountCents, order.currency)}
        </span>
      </div>

      {error != null && (
        <div className="mb-lg flex items-start gap-sm p-md bg-error/10 rounded-lg text-on-surface" role="alert">
          <Icon name="error" className="text-error shrink-0" />
          <p className="font-body-sm text-body-sm break-words">{describeError(error)}</p>
        </div>
      )}

      {processing ? (
        <div className="w-full py-lg flex items-center justify-center gap-md text-on-surface-variant">
          <Icon name="progress_activity" className="animate-spin" />
          <span className="font-body-md text-body-md">Processing Payment…</span>
        </div>
      ) : (
        <>
          <div ref={containerRef} className="min-h-[52px]" />
          {!ready && error == null && (
            <div className="w-full py-lg flex items-center justify-center gap-md text-on-surface-variant">
              <Icon name="progress_activity" className="animate-spin" />
              <span className="font-body-md text-body-md">Loading secure checkout…</span>
            </div>
          )}
        </>
      )}

      <div className="mt-lg flex items-center justify-center gap-lg text-on-surface-variant/50">
        <Icon name="verified_user" />
        <Icon name="lock" />
        <Icon name="payments" />
      </div>
    </div>
  )
}
