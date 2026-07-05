const KEY = 'eurotransit-theme'
export type Theme = 'light' | 'dark'

function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  document.documentElement.dataset.theme = theme
}

// Set the initial theme before first render (stored preference, else the OS setting).
export function initTheme(): Theme {
  const stored = localStorage.getItem(KEY) as Theme | null
  const theme = stored ?? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  applyTheme(theme)
  return theme
}

export function toggleTheme(): Theme {
  const next: Theme = document.documentElement.classList.contains('dark') ? 'light' : 'dark'
  localStorage.setItem(KEY, next)
  applyTheme(next)
  return next
}
