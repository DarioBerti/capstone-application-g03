import type { ReactNode } from 'react'

type Tone = 'primary' | 'tertiary' | 'accent' | 'success' | 'warning' | 'danger' | 'neutral'

const tones: Record<Tone, string> = {
  primary: 'bg-primary text-on-primary',
  tertiary: 'bg-tertiary-container text-on-tertiary-container',
  accent: 'bg-accent/15 text-tertiary',
  success: 'bg-primary/10 text-primary',
  warning: 'bg-tertiary/10 text-tertiary',
  danger: 'bg-error/10 text-error',
  neutral: 'bg-surface-container-high text-primary',
}

export default function Badge({
  tone = 'neutral',
  children,
  className = '',
}: {
  tone?: Tone
  children: ReactNode
  className?: string
}) {
  return (
    <span className={`inline-block font-label-caps text-label-caps px-sm py-xs rounded uppercase ${tones[tone]} ${className}`}>
      {children}
    </span>
  )
}
