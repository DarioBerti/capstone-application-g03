export const iconButton =
  'flex items-center justify-center w-9 h-9 rounded-full text-on-surface-variant hover:text-primary hover:bg-surface-container transition-colors'

export const navLink = ({ isActive }: { isActive: boolean }) =>
  `font-body-md text-body-md transition-colors duration-200 ${
    isActive ? 'text-primary font-bold' : 'text-on-surface-variant hover:text-primary'
  }`
