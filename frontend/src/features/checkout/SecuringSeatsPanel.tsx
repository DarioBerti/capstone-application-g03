import Icon from '../../components/Icon'

// Shown while the outbound reservation is still settling (the async reservation-result hasn't arrived): the
// backend refuses to open a payment session until the leg is RESERVED, so payment unlocks by itself when the
// live order stream delivers the flip — no user action or refresh needed.
export default function SecuringSeatsPanel() {
  return (
    <div
      className="bg-surface-container-lowest p-xl rounded-xl shadow-sm border border-outline-variant/10"
      aria-live="polite"
    >
      <h1 className="font-headline-md text-headline-md text-on-surface mb-lg">Securing your seats…</h1>
      <div className="flex items-center gap-md text-on-surface-variant">
        <Icon name="progress_activity" className="animate-spin text-primary" />
        <p className="font-body-sm text-body-sm">
          We're confirming your reservation with the operator. Payment unlocks in a moment.
        </p>
      </div>
    </div>
  )
}
