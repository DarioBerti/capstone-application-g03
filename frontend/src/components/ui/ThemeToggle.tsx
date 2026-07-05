import { useState } from 'react'
import Icon from '../Icon'
import { toggleTheme } from '../../lib/theme'

export default function ThemeToggle() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  return (
    <button
      onClick={() => setDark(toggleTheme() === 'dark')}
      className="flex items-center justify-center w-9 h-9 rounded-full text-on-surface-variant hover:text-primary hover:bg-surface-container transition-colors"
      aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
    >
      <Icon name={dark ? 'light_mode' : 'dark_mode'} />
    </button>
  )
}
