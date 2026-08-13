import type {
  AllocationTargets,
  Diversification,
  EssentialExpenseEstimate,
  Projection,
  WealthPyramid,
} from '@/types/api'

/**
 * A deliberately imperfect portfolio: the emergency fund overshoots, equity is well under
 * target and alternatives are almost absent. A demo that scores 100 shows none of the UI that
 * matters — the gap badges, the excess-cash line, the "money to move" figure.
 */
export const mockAllocationTargets: AllocationTargets = {
  monthlyEssentialExpenses: 1850,
  safetyNetMonths: 6,
  realEstatePct: 30,
  equityPct: 50,
  cryptoPct: 10,
  alternativePct: 10,
}

export const mockWealthPyramid: WealthPyramid = {
  // Every figure below is what WealthPyramidService would actually produce from these accounts:
  // total assets include the current-account cash, allocatable removes both the cushion and that
  // cash, and each tier's percentages divide allocatable. A fixture whose arithmetic disagrees
  // with the service teaches the UI to render an impossible payload.
  totalAssetsEur: 341700,
  allocatableEur: 319200,
  safetyNet: {
    valueEur: 18200,
    dailyCashEur: 4300,
    targetEur: 11100,
    coverage: 1.6396,
    excessEur: 7100,
    known: true,
    score: 87,
  },
  tiers: [
    {
      tier: 'REAL_ESTATE',
      targetEur: 95760,
      valueEur: 138000,
      actualPercent: 43.23,
      targetPercent: 30,
      gapPercent: 13.23,
      accounts: [{ accountId: 8, name: 'Appartement Lyon', color: '#a855f7', valueEur: 138000 }],
    },
    {
      tier: 'EQUITY',
      targetEur: 159600,
      valueEur: 142400,
      actualPercent: 44.61,
      targetPercent: 50,
      gapPercent: -5.39,
      accounts: [
        { accountId: 2, name: 'PEA', color: '#6366f1', valueEur: 96400 },
        { accountId: 5, name: 'Assurance vie', color: '#8b5cf6', valueEur: 46000 },
      ],
    },
    {
      tier: 'CRYPTO',
      targetEur: 31920,
      valueEur: 32800,
      actualPercent: 10.28,
      targetPercent: 10,
      gapPercent: 0.28,
      accounts: [{ accountId: 3, name: 'Binance', color: '#f97316', valueEur: 32800 }],
    },
    {
      tier: 'ALTERNATIVE',
      targetEur: 31920,
      valueEur: 6000,
      actualPercent: 1.88,
      targetPercent: 10,
      gapPercent: -8.12,
      accounts: [{ accountId: 7, name: 'Or physique', color: '#eab308', valueEur: 6000 }],
    },
  ],
  score: {
    global: 91,
    allocation: 86,
    misplacedPercent: 13.51,
    cryptoPenalty: 0.1,
    leverageBonus: 4.28,
    cryptoTopTenShare: 72.5,
    loanToValue: 51.4,
  },
}

export const mockExpenseEstimate: EssentialExpenseEstimate = {
  estimate: 1912.4,
  monthsObserved: 6,
  excludedTransferCount: 11,
}

/**
 * Deliberately imperfect too: technology-heavy, US-heavy, and with lines the profiles cannot
 * place — so the coverage line, the correction list and both of its states are all exercised.
 * One was never looked up (a refresh may still fix it), the other was and still has no domicile,
 * which is the case only a hand-made override can close.
 */
export const mockDiversification: Diversification = {
  totalValueEur: 142400,
  classifiedValueEur: 131800,
  unclassifiedValueEur: 10600,
  coveragePercent: 92.56,
  unclassified: [
    {
      ticker: 'FCPE-DEMO',
      name: 'Actions Monde (FCPE)',
      accountId: 4,
      valueEur: 8200,
      sectorMissing: true,
      countryMissing: true,
      profileLooked: false,
    },
    {
      ticker: 'MC.PA',
      name: 'LVMH',
      accountId: 3,
      valueEur: 2400,
      sectorMissing: false,
      countryMissing: true,
      profileLooked: true,
    },
  ],
  sectors: {
    score: 78,
    effectiveCount: 4.68,
    targetCount: 6,
    basis: 'MIXED',
    slices: [
      { label: 'technology', percent: 31.4 },
      { label: 'financial_services', percent: 18.2 },
      { label: 'healthcare', percent: 13.7 },
      { label: 'consumer_cyclical', percent: 11.1 },
      { label: 'industrials', percent: 9.8 },
      { label: 'energy', percent: 6.3 },
      { label: 'basic_materials', percent: 4.2 },
      { label: 'utilities', percent: 3.1 },
      { label: 'real_estate', percent: 2.2 },
    ],
  },
  countries: {
    score: 71,
    effectiveCount: 2.14,
    targetCount: 3,
    basis: 'MIXED',
    slices: [
      { label: 'US', percent: 62.8 },
      { label: 'FR', percent: 14.3 },
      { label: 'JP', percent: 7.1 },
      { label: 'GB', percent: 5.4 },
      { label: 'DE', percent: 4.6 },
      { label: 'NL', percent: 3.2 },
      { label: 'CH', percent: 2.6 },
    ],
  },
}

/**
 * Generated rather than hand-written: twenty years x four scenarios is 84 points, and the shape
 * that matters (each curve above the previous, contributions growing linearly) is the arithmetic
 * itself.
 */
export function mockProjection(years: number): Projection {
  const base = 96400
  const monthly = 300
  const rates: { key: Projection['scenarios'][number]['key']; annualPercent: number }[] = [
    { key: 'LIVRET_A', annualPercent: 2 },
    { key: 'PESSIMISTIC', annualPercent: 5 },
    { key: 'REALISTIC', annualPercent: 7.5 },
    { key: 'OPTIMISTIC', annualPercent: 10 },
  ]

  return {
    baseValueEur: base,
    monthlyInflowEur: monthly,
    years,
    scenarios: rates.map(({ key, annualPercent }) => {
      const monthlyRate = Math.pow(1 + annualPercent / 100, 1 / 12) - 1
      let value = base
      let contributed = base
      const points = [{ date: isoMonthEnd(0), valueEur: base, contributedEur: base }]
      for (let i = 1; i <= years * 12; i++) {
        value = value * (1 + monthlyRate) + monthly
        contributed += monthly
        if (i % 12 === 0) {
          points.push({
            date: isoMonthEnd(i),
            valueEur: Math.round(value * 100) / 100,
            contributedEur: Math.round(contributed * 100) / 100,
          })
        }
      }
      return { key, annualPercent, points }
    }),
  }
}

function isoMonthEnd(monthsFromNow: number): string {
  const now = new Date()
  const d = new Date(now.getFullYear(), now.getMonth() + monthsFromNow + 1, 0)
  return d.toISOString().slice(0, 10)
}
