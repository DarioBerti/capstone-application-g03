interface IconProps {
  name: string
  className?: string
  filled?: boolean
  title?: string
}

// Thin wrapper over Google's Material Symbols Outlined font (loaded in index.html). `filled` toggles the
// FILL axis; icons are decorative by default (aria-hidden) unless a `title` gives them a label.
export default function Icon({ name, className, filled, title }: IconProps) {
  return (
    <span
      className={`material-symbols-outlined${className ? ` ${className}` : ''}`}
      style={filled ? { fontVariationSettings: "'FILL' 1" } : undefined}
      aria-hidden={title ? undefined : true}
      role={title ? 'img' : undefined}
      aria-label={title}
    >
      {name}
    </span>
  )
}
