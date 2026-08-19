import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { GoalProgress } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key} ${Object.values(options).join(' ')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const { SavingsRateCard } = await import('./SavingsRateCard')

const TODAY = '2026-08-19'

function plan(partial: Partial<GoalProgress> & { id: number }): GoalProgress {
  return {
    name: 'Plan',
    type: 'RECURRING_INVESTMENT',
    createdAt: '2026-01-01T00:00:00Z',
    historyStartMonth: null,
    accounts: [],
    currentTotal: 0,
    targetAmount: null,
    deadline: null,
    percentComplete: null,
    monthlyNeeded: null,
    avgMonthlyContribution: null,
    surplus: null,
    monthsLeft: 0,
    isOnTrack: true,
    monthlyAmount: 400,
    expectedReturn: null,
    startDate: null,
    endDate: null,
    allocations: [],
    ...partial,
  }
}

const noop = () => {}

describe('SavingsRateCard', () => {
  it('reports contributions over income as a percentage', () => {
    render(
      <SavingsRateCard
        plans={[plan({ id: 1, monthlyAmount: 400 }), plan({ id: 2, monthlyAmount: 200 })]}
        monthlyNetIncome={3000}
        today={TODAY}
        onOpenSettings={noop}
      />,
    )

    expect(screen.getByText('20.0 %')).toBeInTheDocument()
  })

  it('says which side of the French average the member is on', () => {
    const { rerender } = render(
      <SavingsRateCard plans={[plan({ id: 1, monthlyAmount: 600 })]} monthlyNetIncome={3000} today={TODAY} onOpenSettings={noop} />,
    )
    expect(screen.getByText('goals.savingsRate.above 17.5')).toBeInTheDocument()

    rerender(
      <SavingsRateCard plans={[plan({ id: 1, monthlyAmount: 100 })]} monthlyNetIncome={3000} today={TODAY} onOpenSettings={noop} />,
    )
    expect(screen.getByText('goals.savingsRate.below 17.5')).toBeInTheDocument()
  })

  // A plan is a record the member keeps; one that has not started or has ended is still on the
  // page but is not money going out this month.
  it('counts only the plans running this month', () => {
    render(
      <SavingsRateCard
        plans={[
          plan({ id: 1, monthlyAmount: 300 }),
          plan({ id: 2, monthlyAmount: 900, startDate: '2027-01-01' }),
          plan({ id: 3, monthlyAmount: 900, endDate: '2026-01-01' }),
        ]}
        monthlyNetIncome={3000}
        today={TODAY}
        onOpenSettings={noop}
      />,
    )

    expect(screen.getByText('10.0 %')).toBeInTheDocument()
  })

  // Inventing a denominator would be worse than saying nothing: the figure would look like a
  // measurement and be an artefact.
  it('asks for an income instead of showing a rate it cannot compute', () => {
    const onOpenSettings = vi.fn()
    render(
      <SavingsRateCard plans={[plan({ id: 1 })]} monthlyNetIncome={null} today={TODAY} onOpenSettings={onOpenSettings} />,
    )

    expect(screen.getByText('goals.savingsRate.needsIncome')).toBeInTheDocument()
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('goals.savingsRate.goToSettings'))
    expect(onOpenSettings).toHaveBeenCalled()
  })

  it('renders nothing when no plan is paying in', () => {
    const { container } = render(
      <SavingsRateCard
        plans={[plan({ id: 1, monthlyAmount: 500, endDate: '2026-01-01' })]}
        monthlyNetIncome={3000}
        today={TODAY}
        onOpenSettings={noop}
      />,
    )

    expect(container).toBeEmptyDOMElement()
  })
})
