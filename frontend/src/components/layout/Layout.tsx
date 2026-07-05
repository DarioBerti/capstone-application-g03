import { Outlet } from 'react-router-dom'
import Header from './Header'
import Footer from './Footer'

export default function Layout() {
  return (
    <div className="min-h-screen flex flex-col bg-surface text-on-surface font-body-md">
      <Header />
      <Outlet />
      <Footer />
    </div>
  )
}
