import type { OrderStatus, RejectionReason } from '../../types'

// Human-facing copy for the saga states + terminal rejection reasons (mapped from the backend enums).
export const STATUS_LABEL: Record<OrderStatus, string> = {
  DRAFT: 'Draft',
  HELD: 'Seats held',
  PAYING: 'Processing payment',
  AUTHORIZED: 'Payment authorized',
  CONFIRMED: 'Confirmed',
  REJECTED: 'Rejected',
}

export const REJECTION_MESSAGE: Record<RejectionReason, string> = {
  SOLD_OUT: 'Seats sold out while completing your booking. Please search again.',
  PAYMENT_DECLINED: 'Your payment was declined. Try again with another method.',
  RESERVATION_EXPIRED: 'Your seat reservation expired before payment completed.',
  EXPIRED_NO_PAYMENT: 'The hold window elapsed before payment. Your seats were released.',
  SERVICE_UNAVAILABLE: 'Payment was accepted but the booking could not be completed. You were not charged.',
}

export function rejectionMessage(reason: RejectionReason | null): string {
  return reason ? REJECTION_MESSAGE[reason] : 'Your booking was rejected.'
}
