import React from 'react'
import ReactDOM from 'react-dom/client'
import { Provider } from 'react-redux'
import { RouterProvider } from 'react-router-dom'
import { store } from './app/store'
import { router } from './routes/router'
import { initAuth } from './auth'
import { initTheme } from './lib/theme'
import './index.css'

initTheme()

// Restore any existing SSO session before the first render so the UI knows immediately whether the user is
// signed in (it never blocks behind a forced login — sign-in is an explicit action in the header).
initAuth().finally(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <Provider store={store}>
        <RouterProvider router={router} />
      </Provider>
    </React.StrictMode>,
  )
})
