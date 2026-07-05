import Badge from '../../components/ui/Badge'
import { STATUS_LABEL } from '../booking/statusText'
import type { OrderStatus } from '../../types'

const TONE: Record<OrderStatus, 'success' | 'warning' | 'danger' | 'neutral' | 'primary'> = {
  DRAFT: 'neutral',
  HELD: 'warning',
  PAYING: 'warning',
  AUTHORIZED: 'primary',
  CONFIRMED: 'success',
  REJECTED: 'danger',
}

export default function OrderStatusBadge({ status }: { status: OrderStatus }) {
  return <Badge tone={TONE[status]}>{STATUS_LABEL[status]}</Badge>
}
