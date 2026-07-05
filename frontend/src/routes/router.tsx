import { createBrowserRouter } from 'react-router-dom'
import Layout from '../components/layout/Layout'
import HomePage from '../features/home/HomePage'
import SearchPage from '../features/search/SearchPage'
import CheckoutPage from '../features/checkout/CheckoutPage'
import MyBookingsPage from '../features/orders/MyBookingsPage'
import ProfilePage from '../features/profile/ProfilePage'
import NotFoundPage from './NotFoundPage'

export const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/search', element: <SearchPage /> },
      { path: '/orders/:orderId', element: <CheckoutPage /> },
      { path: '/orders', element: <MyBookingsPage /> },
      { path: '/profile', element: <ProfilePage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
