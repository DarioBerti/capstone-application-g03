import { describe, expect, test, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import StationCombobox from './StationCombobox'
import type { Station } from '../../types'

const stations: Station[] = [
  { code: '8301700', name: 'Milano Centrale', city: 'Milano', country: 'IT' },
  { code: '8308409', name: 'Roma Termini', city: 'Roma', country: 'IT' },
]

function renderBox(props: { value: string; onChange?: (code: string) => void; anywhereOption?: boolean }) {
  return render(
    <StationCombobox
      id="from"
      label="FROM"
      icon="location_on"
      placeholder="Anywhere"
      stations={stations}
      value={props.value}
      onChange={props.onChange ?? (() => {})}
      anywhereOption={props.anywhereOption}
    />,
  )
}

describe('StationCombobox anywhere option', () => {
  test('shows Anywhere as the resolved selection for an empty value', () => {
    renderBox({ value: '', anywhereOption: true })
    expect(screen.getByRole('combobox')).toHaveValue('Anywhere')
  })

  test('lists Anywhere as the first option when browsing', async () => {
    renderBox({ value: '', anywhereOption: true })
    await userEvent.click(screen.getByRole('combobox'))
    const options = screen.getAllByRole('option')
    expect(within(options[0]).getByText('Anywhere')).toBeInTheDocument()
    expect(options).toHaveLength(3)
  })

  test('picking Anywhere clears the value', async () => {
    const onChange = vi.fn()
    renderBox({ value: '8301700', onChange, anywhereOption: true })
    const input = screen.getByRole('combobox')
    expect(input).toHaveValue('Milano Centrale')
    await userEvent.click(input)
    await userEvent.click(screen.getByRole('button', { name: /anywhere/i }))
    expect(onChange).toHaveBeenCalledWith('')
  })

  test('a station search does not surface Anywhere', async () => {
    renderBox({ value: '', anywhereOption: true })
    const input = screen.getByRole('combobox')
    await userEvent.clear(input)
    await userEvent.type(input, 'roma')
    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(1)
    expect(within(options[0]).getByText('Roma Termini')).toBeInTheDocument()
  })

  test('without the flag an empty value stays a placeholder', () => {
    renderBox({ value: '' })
    expect(screen.getByRole('combobox')).toHaveValue('')
    expect(screen.getByRole('combobox')).toHaveAttribute('placeholder', 'Anywhere')
  })
})
