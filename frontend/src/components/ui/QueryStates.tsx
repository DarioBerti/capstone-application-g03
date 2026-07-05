import type { ReactNode } from 'react'
import Icon from '../Icon'

// Turns an RTK Query / Axios error into a user-facing "status — body" line. Never swallow the detail —
// surfacing the backend's message is a first-class part of "degrade, don't crash".
export function describeError(error: unknown): string {
  if (!error) return 'Unknown error'
  const e = error as { status?: number; data?: unknown; message?: string }
  const status = e.status ? `HTTP ${e.status}` : ''
  const body =
    typeof e.data === 'string' ? e.data : e.data != null ? JSON.stringify(e.data) : (e.message ?? '')
  return [status, body].filter(Boolean).join(' — ') || 'Request failed'
}

export function LoadingBlock({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-sm py-xl text-on-surface-variant" role="status">
      <Icon name="progress_activity" className="animate-spin" />
      <span className="font-body-md text-body-md">{label}</span>
    </div>
  )
}

export function EmptyState({ icon = 'inbox', title, hint }: { icon?: string; title: string; hint?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center text-center gap-sm py-xl text-on-surface-variant">
      <Icon name={icon} className="text-4xl text-outline" />
      <p className="font-title-lg text-title-lg text-on-surface">{title}</p>
      {hint && <p className="font-body-sm text-body-sm max-w-sm">{hint}</p>}
    </div>
  )
}

export function ErrorState({ error, onRetry }: { error?: unknown; onRetry?: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center text-center gap-sm py-xl" role="alert">
      <Icon name="error" className="text-4xl text-error" />
      <p className="font-title-lg text-title-lg text-on-surface">Something went wrong</p>
      <p className="font-body-sm text-body-sm text-on-surface-variant max-w-md break-words">{describeError(error)}</p>
      {onRetry && (
        <button onClick={onRetry} className="mt-sm text-primary font-bold hover:underline">
          Try again
        </button>
      )}
    </div>
  )
}
