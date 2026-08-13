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
  totalAssetsEur: 337400,
  allocatableEur: 326300,
  safetyNet: {
    valueEur: 18200,
    targetEur: 11100,
    coverage: 1.6396,
    excessEur: 7100,
    known: true,
    score: 87,
  },
  tiers: [
    {
      tier: 'SAFETY_NET',
      valueEur: 7100,
      actualPercent: 2.18,
      targetPercent: 0,
      gapPercent: 2.18,
      accounts: [],
    },
    {
      tier: 'REAL_ESTATE',
      valueEur: 138000,
      actualPercent: 42.29,
      targetPercent: 30,
      gapPercent: 12.29,
      accounts: [{ accountId: 8, name: 'Appartement Lyon', color: '#a855f7', valueEur: 138000 }],
    },
    {
      tier: 'EQUITY',
      valueEur: 142400,
      actualPercent: 43.64,
      targetPercent: 50,
      gapPercent: -6.36,
      accounts: [
        { accountId: 2, name: 'PEA', color: '#6366f1', valueEur: 96400 },
        { accountId: 5, name: 'Assurance vie', color: '#8b5cf6', valueEur: 46000 },
      ],
    },
    {
      tier: 'CRYPTO',
      valueEur: 32800,
      actualPercent: 10.05,
      targetPercent: 10,
      gapPercent: 0.05,
      accounts: [{ accountId: 3, name: 'Binance', color: '#f97316', valueEur: 32800 }],
    },
    {
      tier: 'ALTERNATIVE',
      valueEur: 6000,
      actualPercent: 1.84,
      targetPercent: 10,
      gapPercent: -8.16,
      accounts: [{ accountId: 7, name: 'Or physique', color: '#eab308', valueEur: 6000 }],
    },
  ],
  score: {
    global: 79,
    allocation: 86,
    misplacedPercent: 14.42,
    cryptoPenalty: 0.6,
    leverageBonus: 4.2,
    cryptoTopTenShare: 72.5,
    loanToValue: 51.4,
  },
}

export const mockExpenseEstimate: EssentialExpenseEstimate = {
  estimate: 1912.4,
  monthsObserved: 6,
  excludedTransferCount: 11,
}
