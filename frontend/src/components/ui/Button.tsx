import type { ButtonHTMLAttributes, ReactNode } from 'react'

type Variant = 'primary' | 'accent' | 'secondary' | 'ghost'

const base =
  'inline-flex items-center justify-center gap-sm font-bold rounded-lg transition-all active:scale-[0.98] ' +
  'disabled:opacity-50 disabled:pointer-events-none focus-visible:outline-none focus-visible:ring-2 ' +
  'focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-surface'

const variants: Record<Variant, string> = {
  // "Motion" teal gradient (search / primary journeys).
  primary: 'motion-gradient text-on-primary hover:brightness-110 shadow-lg',
  // Warm-orange CTA (checkout / pay) — pierces the cool palette; amber needs dark text in both themes.
  accent: 'bg-accent text-on-tertiary-fixed hover:brightness-110 shadow-md',
  secondary: 'border border-primary text-primary hover:bg-primary/5',
  ghost: 'text-on-surface-variant hover:text-primary hover:bg-surface-container',
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  children: ReactNode
}

export default function Button({ variant = 'primary', className = '', children, ...rest }: ButtonProps) {
  return (
    <button className={`${base} ${variants[variant]} px-lg py-md ${className}`} {...rest}>
      {children}
    </button>
  )
}
