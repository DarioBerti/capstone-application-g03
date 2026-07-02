import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { initAuth } from './auth'
import './styles.css'

// Restore any existing SSO session before the first render so the UI knows immediately whether the user
// is signed in (it never blocks behind a forced login — sign-in is an explicit action in the header).
initAuth().finally(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  )
})
