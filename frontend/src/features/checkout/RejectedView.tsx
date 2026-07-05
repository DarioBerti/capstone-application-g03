import { useNavigate } from 'react-router-dom'
import Icon from '../../components/Icon'
import { rejectionMessage } from '../booking/statusText'
import type { Order } from '../../types'

export default function RejectedView({ order }: { order: Order }) {
  const navigate = useNavigate()
  return (
    <div className="flex flex-col items-center justify-center py-xl max-w-xl mx-auto text-center">
      <div className="w-24 h-24 bg-error-container text-on-error-container rounded-full flex items-center justify-center mb-xl shadow-xl">
        <Icon name="cancel" filled className="text-[48px]" />
      </div>
      <h1 className="font-display-lg text-display-lg-mobile text-on-surface mb-md">Booking not completed</h1>
      <p className="font-body-md text-body-md text-on-surface-variant mb-xl">{rejectionMessage(order.rejectionReason)}</p>
      <div className="flex flex-col md:flex-row gap-md w-full">
        <button
          onClick={() => navigate('/search')}
          className="flex-1 bg-primary text-on-primary py-lg rounded-xl font-bold flex items-center justify-center gap-md shadow-lg active:scale-95 transition-all"
        >
          <Icon name="search" />
          Search again
        </button>
        <button
          onClick={() => navigate('/orders')}
          className="flex-1 bg-surface-container text-on-surface-variant py-lg rounded-xl font-bold flex items-center justify-center gap-md active:scale-95 transition-all"
        >
          My bookings
        </button>
      </div>
    </div>
  )
}
