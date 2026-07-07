import { describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import Pager from './Pager'

describe('Pager', () => {
  test('renders nothing when there is a single page', () => {
    const { container } = render(<Pager page={0} totalPages={1} onPage={() => {}} />)
    expect(container).toBeEmptyDOMElement()
  })

  test('shows the current position and disables previous on the first page', () => {
    render(<Pager page={0} totalPages={3} onPage={() => {}} />)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next page' })).toBeEnabled()
  })

  test('disables next on the last page', () => {
    render(<Pager page={2} totalPages={3} onPage={() => {}} />)
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeEnabled()
  })

  test('steps to the adjacent page on click', () => {
    const onPage = vi.fn()
    render(<Pager page={1} totalPages={3} onPage={onPage} />)
    fireEvent.click(screen.getByRole('button', { name: 'Previous page' }))
    expect(onPage).toHaveBeenCalledWith(0)
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(onPage).toHaveBeenCalledWith(2)
  })
})
