import { useEffect, useRef, useState, type FormEvent } from 'react'
import Icon from '../../components/Icon'
import { describeError } from '../../components/ui/QueryStates'
import { formatCents } from '../../lib/format'
import { loadPayPalSdk, type PayPalCardFields, type PayPalCardFieldsStyle } from '../../lib/paypal'
import { useCreatePayPalSessionMutation } from './paymentSessionApi'
import type { Order } from '../../types'

/**
 * Real card entry via PayPal Card Fields. On mount we open a session (Orders → Payments creates a PayPal order for
 * this booking), load PayPal's SDK, and render its **hosted** Name/Number/Expiry/CVV iframes. The card is entered
 * inside PayPal's frames — it never touches our bundle. On submit PayPal attaches the card + runs 3DS, and
 * `onApprove` hands us the approved order id, which we forward to `onPay` (→ POST /pay). No card field is ever
 * read or transmitted by us.
 */
// Mirror the storefront's `body-md` role + on-surface/outline colours onto PayPal's iframe input so the hosted
// fields read as the same field as the rest of the form. Colours are resolved once at render (the `.dark` class);
// Inter falls back to system-ui inside PayPal's frame. The visible box (border/bg) stays on our outer container,
// so padding is 0 here — the container's `px-md` supplies the inset.
function cardFieldStyle(): PayPalCardFieldsStyle {
  const dark = document.documentElement.classList.contains('dark')
  return {
    input: {
      'font-family': 'Inter, system-ui, sans-serif',
      'font-size': '16px',
      'font-weight': '400',
      color: dark ? 'rgb(224, 231, 245)' : 'rgb(11, 28, 48)',
      padding: '0',
    },
    '::placeholder': { color: dark ? 'rgb(134, 147, 143)' : 'rgb(109, 122, 119)' },
  }
}

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
  const cardFieldsRef = useRef<PayPalCardFields | null>(null)
  const [ready, setReady] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    let cancelled = false
    setReady(false)
    setError(null)
    ;(async () => {
      try {
        const session = await createSession(order.orderId).unwrap()
        const paypal = await loadPayPalSdk(session.clientId, session.clientToken)
        if (cancelled) return
        const cardFields = paypal.CardFields({
          createOrder: async () => session.payPalOrderId,
          onApprove: ({ orderID }) => onPay(orderID),
          onError: (err) => {
            setSubmitting(false)
            setError(err)
          },
          style: cardFieldStyle(),
        })
        if (!cardFields.isEligible()) {
          setError(new Error('Card payments are not available for this account.'))
          return
        }
        await Promise.all([
          cardFields.NameField().render('#pp-card-name'),
          cardFields.NumberField().render('#pp-card-number'),
          cardFields.ExpiryField().render('#pp-card-expiry'),
          cardFields.CVVField().render('#pp-card-cvv'),
        ])
        if (cancelled) return
        cardFieldsRef.current = cardFields
        setReady(true)
      } catch (err) {
        if (!cancelled) setError(err)
      }
    })()
    return () => {
      cancelled = true
    }
    // Re-open only if the booking changes; onPay/createSession are stable enough for a single checkout.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order.orderId])

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    if (!cardFieldsRef.current || submitting || processing) return
    setSubmitting(true)
    setError(null)
    try {
      // Resolves once PayPal has the card + 3DS; onApprove then fires with the approved order id.
      await cardFieldsRef.current.submit()
    } catch (err) {
      setSubmitting(false)
      setError(err)
    }
  }

  const busy = submitting || processing
  const field = 'w-full h-[52px] px-md bg-surface-container-low border border-outline-variant rounded-lg'
  const label = 'font-label-caps text-label-caps text-on-surface-variant'

  return (
    <div className="bg-surface-container-lowest p-xl rounded-xl shadow-sm border border-outline-variant/10">
      <h1 className="font-headline-md text-headline-md text-on-surface mb-lg">Payment Details</h1>
      <div className="mb-lg flex items-start gap-sm p-md bg-secondary-container/30 rounded-lg text-on-secondary-container">
        <Icon name="lock" className="text-primary shrink-0" />
        <p className="font-body-sm text-body-sm">
          Your card is entered directly in PayPal's secure fields — it never touches EuroTransit's servers.
        </p>
      </div>

      {error != null && (
        <div className="mb-lg flex items-start gap-sm p-md bg-error/10 rounded-lg text-on-surface" role="alert">
          <Icon name="error" className="text-error shrink-0" />
          <p className="font-body-sm text-body-sm break-words">{describeError(error)}</p>
        </div>
      )}

      <form className="flex flex-col gap-lg" onSubmit={onSubmit}>
        <div className="flex flex-col gap-sm">
          <label className={label}>CARDHOLDER NAME</label>
          <div id="pp-card-name" className={field} />
        </div>
        <div className="flex flex-col gap-sm">
          <label className={label}>CARD NUMBER</label>
          <div id="pp-card-number" className={field} />
        </div>
        <div className="grid grid-cols-2 gap-md">
          <div className="flex flex-col gap-sm">
            <label className={label}>EXPIRY DATE</label>
            <div id="pp-card-expiry" className={field} />
          </div>
          <div className="flex flex-col gap-sm">
            <label className={label}>CVV</label>
            <div id="pp-card-cvv" className={field} />
          </div>
        </div>
        <button
          type="submit"
          disabled={!ready || busy}
          className="w-full py-lg bg-accent text-on-background rounded-xl font-bold text-lg hover:brightness-110 active:scale-[0.98] transition-all shadow-md flex items-center justify-center gap-md disabled:opacity-60 disabled:pointer-events-none"
        >
          {busy ? (
            <>
              <Icon name="progress_activity" className="animate-spin" />
              <span>Processing Payment…</span>
            </>
          ) : (
            <span>{ready ? `Pay ${formatCents(order.amountCents, order.currency)}` : 'Loading secure fields…'}</span>
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
