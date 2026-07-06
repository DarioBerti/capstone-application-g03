import { describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import DateStrip from './DateStrip'

// 2026-07-06 is a Monday; the window centres it as 03–09 Jul (clamped to minDate).
describe('DateStrip', () => {
  test('marks the selected day as pressed', () => {
    render(<DateStrip value="2026-07-06" minDate="2026-07-01" onChange={() => {}} />)
    expect(screen.getByRole('button', { name: 'Mon 6 Jul' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('selecting another visible day calls onChange with that day', () => {
    const onChange = vi.fn()
    render(<DateStrip value="2026-07-06" minDate="2026-07-01" onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', { name: 'Sat 4 Jul' }))
    expect(onChange).toHaveBeenCalledWith('2026-07-04')
  })

  test('disables the previous-days arrow at the earliest allowed day', () => {
    render(<DateStrip value="2026-07-01" minDate="2026-07-01" onChange={() => {}} />)
    expect(screen.getByRole('button', { name: 'Previous days' })).toBeDisabled()
  })

  test('paging forward reveals later days', () => {
    render(<DateStrip value="2026-07-06" minDate="2026-07-01" onChange={() => {}} />)
    expect(screen.queryByRole('button', { name: 'Mon 13 Jul' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next days' }))
    expect(screen.getByRole('button', { name: 'Mon 13 Jul' })).toBeInTheDocument()
  })
})
