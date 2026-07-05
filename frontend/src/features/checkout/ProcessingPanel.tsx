import Icon from '../../components/Icon'
import { STATUS_LABEL } from '../booking/statusText'
import type { OrderStatus } from '../../types'

// Shown in the middle column once payment is triggered (PAYING/AUTHORIZED): the async saga is advancing and
// the UI observes it over SSE to a terminal state.
export default function ProcessingPanel({ status }: { status: OrderStatus }) {
  return (
    <div className="bg-surface-container-lowest p-xl rounded-xl shadow-sm border border-outline-variant/10 flex flex-col items-center justify-center text-center gap-md min-h-[320px]">
      <Icon name="progress_activity" className="animate-spin text-primary text-5xl" />
      <h2 className="font-headline-md text-headline-md">{STATUS_LABEL[status]}…</h2>
      <p className="font-body-md text-body-md text-on-surface-variant max-w-sm">
        We're securing your seats and completing payment. This page updates live — no need to refresh.
      </p>
    </div>
  )
}
