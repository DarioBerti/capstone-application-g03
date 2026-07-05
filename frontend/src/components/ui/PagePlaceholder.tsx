import Icon from '../Icon'

// Themed placeholder for routes not yet built out (keeps the shell consistent while screens land).
export default function PagePlaceholder({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <main className="flex-grow w-full max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-xl">
      <div className="flex flex-col items-center justify-center text-center gap-md py-xl">
        <div className="w-16 h-16 rounded-full bg-primary/10 flex items-center justify-center">
          <Icon name="construction" className="text-primary text-3xl" />
        </div>
        <h1 className="font-headline-md text-headline-md">{title}</h1>
        {subtitle && <p className="font-body-md text-body-md text-on-surface-variant max-w-md">{subtitle}</p>}
      </div>
    </main>
  )
}
