import { useState } from 'react'
import Icon from '../Icon'
import { toggleTheme } from '../../lib/theme'
import { iconButton } from './classRecipes'

export default function ThemeToggle() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  return (
    <button
      onClick={() => setDark(toggleTheme() === 'dark')}
      className={iconButton}
      aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
    >
      <Icon name={dark ? 'light_mode' : 'dark_mode'} />
    </button>
  )
}
