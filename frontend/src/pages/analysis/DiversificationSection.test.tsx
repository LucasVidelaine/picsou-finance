import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DiversificationSection } from './DiversificationSection'
import type { Diversification } from '@/types/api'

// No QueryClient here: the section and the editor it embeds both reach for TanStack Query, and
// this suite is about what the card renders, not about fetching. Same approach as
// AnalysisPage.test.tsx.
vi.mock('@/features/analysis/hooks', () => ({
  useRefreshSecurityProfiles: () => ({ mutate: vi.fn(), isPending: false }),
  useHoldingClassification: () => ({ data: undefined, isPending: true }),
  useClassifyHolding: () => ({ mutate: vi.fn(), isPending: false }),
}))

// Translate the two label namespaces the way the app does, and fall back to the raw value —
// that fallback is the contract for a sector or country key the locales do not map.
const LABELS: Record<string, string> = {
  'holdings.insight.sectorNames.technology': 'Technology',
  'holdings.insight.countryNames.US': 'United States',
  'holdings.insight.others': 'Others',
}
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: unknown) => {
      if (LABELS[key]) return LABELS[key]
      if (typeof opts === 'string') return opts
      if (opts && typeof opts === 'object') return `${key}:${Object.values(opts).join(',')}`
      return key
    },
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

function data(overrides: Partial<Diversification> = {}): Diversification {
  return {
    totalValueEur: 10000,
    classifiedValueEur: 10000,
    unclassifiedValueEur: 0,
    coveragePercent: 100,
    unclassified: [],
    sectors: {
      score: 80, effectiveCount: 4.8, targetCount: 6, basis: 'EXPOSURE', classifiedValueEur: 10000, coveragePercent: 100,
      slices: [
        { label: 'technology', percent: 60 },
        { label: 'unmapped_sector', percent: 40 },
      ],
    },
    countries: {
      score: 70, effectiveCount: 2.1, targetCount: 3, basis: 'EXPOSURE', classifiedValueEur: 10000, coveragePercent: 100,
      slices: [{ label: 'US', percent: 100 }],
    },
    ...overrides,
  }
}

describe('DiversificationSection', () => {
  it('translates known keys and renders an unmapped one verbatim', () => {
    render(<DiversificationSection data={data()} />)

    expect(screen.getByText('Technology')).toBeInTheDocument()
    expect(screen.getByText('United States')).toBeInTheDocument()
    // A key the locales do not carry must still render as something, never as a blank slice.
    expect(screen.getByText('unmapped_sector')).toBeInTheDocument()
  })

  it('shows both scores with their effective position counts', () => {
    render(<DiversificationSection data={data()} />)

    expect(screen.getByText('80 / 100')).toBeInTheDocument()
    expect(screen.getByText('70 / 100')).toBeInTheDocument()
    expect(screen.getByText('analysis.diversification.effective:4.8,6')).toBeInTheDocument()
  })

  it('states the coverage and lists what could not be placed', () => {
    render(
      <DiversificationSection
        data={data({
          classifiedValueEur: 6000,
          unclassifiedValueEur: 4000,
          coveragePercent: 60,
          unclassified: [
            {
              ticker: 'MC.PA',
              name: 'LVMH',
              accountId: 3,
              valueEur: 4000,
              sectorMissing: false,
              countryMissing: true,
              profileLooked: true,
            },
          ],
        })}
      />,
    )

    // A bar computed over 60% of the portfolio must not read as one computed over all of it.
    expect(screen.getByText('analysis.diversification.coverage:60')).toBeInTheDocument()
    // Naming the line is the point: a bare total says the score is wrong without saying why.
    expect(screen.getByText('LVMH')).toBeInTheDocument()
    expect(screen.getByText('MC.PA')).toBeInTheDocument()
  })

  it('offers the lookup only when something has never been looked up', () => {
    const looked = {
      ticker: 'MC.PA', name: 'LVMH', accountId: 3, valueEur: 4000,
      sectorMissing: false, countryMissing: true, profileLooked: true,
    }
    const { rerender } = render(
      <DiversificationSection data={data({ unclassified: [looked] })} />,
    )
    // Already looked up and still unplaced: only a human can fix it, so promising a refresh
    // would promise a fix that cannot come.
    expect(screen.queryByText('analysis.classification.refresh')).not.toBeInTheDocument()

    rerender(
      <DiversificationSection
        data={data({ unclassified: [{ ...looked, profileLooked: false }] })}
      />,
    )
    expect(screen.getByText('analysis.classification.refresh')).toBeInTheDocument()
  })

  it('notes the mixed basis only when a directly held share contributed', () => {
    const { rerender } = render(<DiversificationSection data={data()} />)
    expect(screen.queryByText('analysis.diversification.basisNote')).not.toBeInTheDocument()

    rerender(
      <DiversificationSection
        data={data({
          countries: { score: 70, effectiveCount: 2.1, targetCount: 3, basis: 'MIXED', classifiedValueEur: 10000, coveragePercent: 100, slices: [{ label: 'US', percent: 100 }] },
        })}
      />,
    )
    expect(screen.getByText('analysis.diversification.basisNote')).toBeInTheDocument()
  })

  // Two 0.4% slices are each below the hairline threshold but add up above it, so they become a
  // visible remainder rather than silently disappearing from a bar that claims to total 100%.
  it('folds hairline slices into an Others remainder', () => {
    render(
      <DiversificationSection
        data={data({
          sectors: {
            score: 50, effectiveCount: 2, targetCount: 6, basis: 'EXPOSURE', classifiedValueEur: 10000, coveragePercent: 100,
            slices: [
              { label: 'technology', percent: 99.2 },
              { label: 'energy', percent: 0.4 },
              { label: 'utilities', percent: 0.4 },
            ],
          },
        })}
      />,
    )

    expect(screen.getByText('Others')).toBeInTheDocument()
    expect(screen.queryByText('energy')).not.toBeInTheDocument()
  })

  it('says so plainly when there is nothing to analyse', () => {
    render(<DiversificationSection data={data({ totalValueEur: 0 })} />)
    expect(screen.getByText('analysis.diversification.noHoldings')).toBeInTheDocument()
  })
})
