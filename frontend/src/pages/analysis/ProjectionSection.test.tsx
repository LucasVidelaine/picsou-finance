import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ProjectionSection } from './ProjectionSection'
import type { Projection } from '@/types/api'

const useProjection = vi.fn()
vi.mock('@/features/analysis/hooks', () => ({
  useProjection: (years: number) => useProjection(years),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && typeof opts === 'object' ? `${key}:${Object.values(opts).join(',')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

// Recharts measures its container, which jsdom reports as 0x0; the chart body is not what these
// assertions are about.
vi.mock('@/components/ui/chart', () => ({
  ChartContainer: ({ children }: { children: React.ReactNode }) => <div data-slot="chart">{children}</div>,
  ChartTooltip: () => null,
  ChartTooltipContent: () => null,
}))

function projection(overrides: Partial<Projection> = {}): Projection {
  const points = [
    { date: '2026-12-31', valueEur: 100000, contributedEur: 100000 },
    { date: '2027-12-31', valueEur: 110000, contributedEur: 103600 },
  ]
  return {
    baseValueEur: 96400,
    monthlyInflowEur: 300,
    years: 20,
    scenarios: [
      { key: 'LIVRET_A', annualPercent: 2, points },
      { key: 'PESSIMISTIC', annualPercent: 5, points },
      { key: 'REALISTIC', annualPercent: 7.5, points },
      { key: 'OPTIMISTIC', annualPercent: 10, points },
    ],
    ...overrides,
  }
}

describe('ProjectionSection', () => {
  beforeEach(() => {
    useProjection.mockReturnValue({ data: projection() })
  })

  it('labels every scenario with the rate the backend sent', () => {
    render(<ProjectionSection />)

    // The rates are never restated client-side: a label that disagrees with the curve behind it
    // is worse than no label.
    expect(screen.getByText('analysis.projection.scenarios.LIVRET_A:2.0')).toBeInTheDocument()
    expect(screen.getByText('analysis.projection.scenarios.REALISTIC:7.5')).toBeInTheDocument()
    expect(screen.getByText('analysis.projection.scenarios.OPTIMISTIC:10.0')).toBeInTheDocument()
  })

  it('keeps the legend in the payload order, not alphabetical', () => {
    render(<ProjectionSection />)

    const labels = screen.getAllByRole('listitem').map((li) => li.textContent)
    expect(labels).toEqual([
      'analysis.projection.scenarios.LIVRET_A:2.0',
      'analysis.projection.scenarios.PESSIMISTIC:5.0',
      'analysis.projection.scenarios.REALISTIC:7.5',
      'analysis.projection.scenarios.OPTIMISTIC:10.0',
    ])
  })

  it('states what it is projecting from, because it is not the net worth', () => {
    render(<ProjectionSection />)
    // The label shares its paragraph with the amount, so the text node is split.
    expect(screen.getByText(/analysis\.projection\.basis/)).toBeInTheDocument()
    expect(screen.getByText('analysis.projection.disclaimer')).toBeInTheDocument()
  })

  it('asks for a plan rather than drawing a flat line at zero', () => {
    useProjection.mockReturnValue({ data: projection({ baseValueEur: 0, monthlyInflowEur: 0 }) })
    render(<ProjectionSection />)

    expect(screen.getByText('analysis.projection.nothingToProject')).toBeInTheDocument()
  })

  it('still projects a portfolio with no recurring plan', () => {
    useProjection.mockReturnValue({ data: projection({ monthlyInflowEur: 0 }) })
    render(<ProjectionSection />)

    expect(screen.queryByText('analysis.projection.nothingToProject')).not.toBeInTheDocument()
  })

  it('offers the three horizons and defaults to twenty years', () => {
    render(<ProjectionSection />)

    expect(screen.getByRole('button', { name: 'analysis.projection.years:10' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'analysis.projection.years:30' })).toBeInTheDocument()
    expect(useProjection).toHaveBeenCalledWith(20)
  })
})
