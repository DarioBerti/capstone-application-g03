import Icon from '../../components/Icon'
import type { OrderStatus } from '../../types'

// The saga progression as the backend defines it — includes AUTHORIZED (which the Stitch mock skipped) and a
// REJECTED error branch. The active step reflects the live order status pushed over SSE.
const STEPS: { status: OrderStatus; label: string; icon: string }[] = [
  { status: 'DRAFT', label: 'DRAFT', icon: 'edit_note' },
  { status: 'HELD', label: 'HELD', icon: 'event_seat' },
  { status: 'PAYING', label: 'PAYING', icon: 'payments' },
  { status: 'AUTHORIZED', label: 'AUTHORIZED', icon: 'verified' },
  { status: 'CONFIRMED', label: 'CONFIRMED', icon: 'confirmation_number' },
]

const ORDER_INDEX: Record<OrderStatus, number> = {
  DRAFT: 0,
  HELD: 1,
  PAYING: 2,
  AUTHORIZED: 3,
  CONFIRMED: 4,
  REJECTED: -1,
}

export default function SagaTracker({ status }: { status: OrderStatus }) {
  const rejected = status === 'REJECTED'
  const current = ORDER_INDEX[status]

  return (
    <div className="bg-surface-container-low p-lg rounded-xl flex flex-col gap-lg">
      <h2 className="font-title-lg text-title-lg text-primary">Order Progress</h2>
      <div className="relative flex flex-col gap-xl">
        <div className="absolute left-[11px] top-4 bottom-4 w-0.5 bg-outline-variant" />
        {STEPS.map((step) => {
          const idx = ORDER_INDEX[step.status]
          const done = !rejected && idx < current
          const active = !rejected && idx === current
          const upcoming = rejected || idx > current
          return (
            <div key={step.status} className={`relative flex items-center gap-md ${upcoming ? 'opacity-40' : ''}`}>
              <div
                className={`z-10 w-6 h-6 rounded-full flex items-center justify-center relative ${
                  done
                    ? 'bg-primary text-on-primary'
                    : active
                      ? 'bg-primary-container text-on-primary-container'
                      : 'bg-outline-variant text-surface-container'
                }`}
              >
                {active && <div className="absolute inset-0 rounded-full bg-primary-container step-pulse" />}
                <Icon name={done ? 'check' : step.icon} className="text-[14px] relative z-20" />
              </div>
              <div>
                <p
                  className={`font-label-caps text-label-caps ${
                    done ? 'text-primary' : active ? 'text-primary-container' : 'text-on-surface-variant'
                  }`}
                >
                  {done ? 'COMPLETED' : active ? 'IN PROGRESS' : 'UPCOMING'}
                </p>
                <p className={`font-title-lg text-title-lg leading-tight ${active ? 'font-bold' : ''}`}>{step.label}</p>
              </div>
            </div>
          )
        })}

        {rejected && (
          <div className="relative flex items-center gap-md">
            <div className="z-10 w-6 h-6 rounded-full bg-error text-on-error flex items-center justify-center">
              <Icon name="close" className="text-[14px]" />
            </div>
            <div>
              <p className="font-label-caps text-label-caps text-error">TERMINAL</p>
              <p className="font-title-lg text-title-lg leading-tight font-bold text-error">REJECTED</p>
            </div>
          </div>
        )}
      </div>

      <div className="mt-md p-md bg-secondary-container/30 rounded-lg border border-outline-variant/20">
        <p className="font-body-sm text-body-sm text-on-secondary-container">
          {rejected
            ? 'This booking could not be completed. Any held seats have been released.'
            : 'Your seats are reserved and held in our system. Complete payment to secure your ticket.'}
        </p>
      </div>
    </div>
  )
}
