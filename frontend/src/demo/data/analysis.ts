import type {
  AllocationTargets,
  EssentialExpenseEstimate,
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
