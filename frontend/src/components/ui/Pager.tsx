import Icon from '../Icon'

const pagerButton =
  'flex items-center justify-center w-10 h-10 rounded-full text-on-surface-variant hover:text-primary hover:bg-surface-container disabled:opacity-40 disabled:pointer-events-none transition-colors'

export default function Pager({
  page,
  totalPages,
  onPage,
}: {
  page: number
  totalPages: number
  onPage: (page: number) => void
}) {
  if (totalPages <= 1) return null
  return (
    <div className="flex items-center justify-center gap-md mt-xl">
      <button
        onClick={() => onPage(Math.max(0, page - 1))}
        disabled={page === 0}
        aria-label="Previous page"
        className={pagerButton}
      >
        <Icon name="chevron_left" />
      </button>
      <span className="font-body-sm text-body-sm text-on-surface-variant tabular-nums">
        Page {page + 1} of {totalPages}
      </span>
      <button
        onClick={() => onPage(Math.min(totalPages - 1, page + 1))}
        disabled={page >= totalPages - 1}
        aria-label="Next page"
        className={pagerButton}
      >
        <Icon name="chevron_right" />
      </button>
    </div>
  )
}
