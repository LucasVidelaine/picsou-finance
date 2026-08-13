# Feature: Wealth pyramid (Analysis)

> Last updated: 2026-08-13

## Context

The Dashboard and Accounts pages measure wealth; nothing said whether it was **well built**. The
Analysis section (`/analysis`) places every asset on the investment pyramid — emergency fund,
property, listed equity, crypto, alternatives — compares the result to targets the member owns,
and scores it out of 100.

Two account types were added for it: `ASSURANCE_VIE` and `SCPI`. The pyramid names both, and no
existing type carried them — a life-insurance policy entered as `OTHER` or `SAVINGS` would have
been scored as an alternative asset or as emergency cash.

## How it works

### Classification

`WealthTier.of(AccountType)` is an **exhaustive switch with no `default`**, so a new account type
stops the compilation there rather than falling into a catch-all. (The frontend's `TYPE_TO_GROUP`
documents the opposite failure: a type added to the enum but not to the map made those accounts
silently vanish from a chart. A `Map` cannot have this property; a switch expression can.)

| Tier | Account types |
|---|---|
| `SAFETY_NET` | `CHECKING`, `SAVINGS`, `LEP`, `LIVRET_A`, `LDDS`, `LIVRET_JEUNE`, `PEL`, `CEL` |
| `REAL_ESTATE` | `REAL_ESTATE`, `SCPI` (and `LOAN`, only so the switch stays total — loans never enter as assets) |
| `EQUITY` | `PEA`, `COMPTE_TITRES`, `EMPLOYEE_SAVINGS`, `ASSURANCE_VIE` |
| `CRYPTO` | `CRYPTO` |
| `ALTERNATIVE` | `OTHER` |

This is **not** `AccountsPage.ASSET_FILTER_MAP`, and PR1 does not touch it: the filters group
accounts the way a user browses them, the pyramid groups them by the role they play. To avoid a
third copy, the mapping lives on the backend and the frontend receives tier membership as *data*.

A wrapper does not determine the asset, so holdings are classified line by line, cheapest test
first and **never a network call** (this endpoint renders a page):

1. the account's tier;
2. `CoinGeckoPriceProvider.supports(ticker)` → `CRYPTO` — an in-memory map lookup, the same first
   test `SecurityInsightService.classify()` uses. This is what puts a bitcoin ETP held in a CTO in
   the crypto tier;
3. `holding_classification.wealth_tier` for `(memberId, ticker)`, which beats both.

Whatever the lines do not account for — cash inside the envelope, and any position the price
providers dropped — stays with the account's own tier as a **residual**. Without it, a
life-insurance euro fund would vanish from the pyramid while still counting in the dashboard's
net worth.

### Aggregation

Copied from `DashboardService.getDashboard`, deliberately and literally: `readableAccounts` →
`sharesFor` → one `valuation` per account → `AccountAccessResolver.weigh` applied **exactly
once**. Weighting twice is the easy bug, and it would make this page disagree with the dashboard
about the same portfolio.

Property enters **net of the mortgage financing it**, via `RealEstateSummaryService.summarize`
rather than a local gross-minus-loans. Re-deriving it would let the Analysis page and the
property card disagree about the same house.

### The safety net, and the allocation base

The cushion's target is an **absolute amount** (`monthlyEssentialExpenses × safetyNetMonths`),
not a share — six months of rent is six months of rent whatever the portfolio is worth.

The four other targets are percentages of what is left once the cushion is doing its job:

```
working    = min(safetyNetValue, target)
allocatable = totalAssets - working
excess      = max(0, safetyNetValue - target)
```

`excess` then enters the allocation vector as the `SAFETY_NET` line **with a target of 0**. That
is the point: cash beyond the target is money in the wrong place, and setting it aside instead
would let a 90 %-cash portfolio score its 10 % remainder perfectly. With it, the five shares
always sum to 100.

### Scoring

| Part | Formula | Why |
|---|---|---|
| Safety net | `100·r` for `r ≤ 1`; `100 − 40·min(1, (r−1)/2)` above, flooring at 60 | Asymmetric: falling short is dangerous and scores in proportion, overshooting is only a drag on yield |
| Allocation | `100 · (1 − ½Σ\|actual − target\|)` | Half the L1 distance is literally *the fraction of allocatable wealth sitting in the wrong tier*, so the UI can state it and be telling the truth |
| Crypto penalty | `10 · w_crypto · max(0, (0.80 − topTenShare)/0.80)`, 0–10 | Scaled by weight: a 2 %-crypto sleeve of small caps loses 0.2 points, a 30 % one loses 3 |
| Leverage bonus | `5 · clamp(ltv/0.60,0,1) · (1 − max(0,(ltv−0.85)/0.15))`, 0–5 | Rewards borrowing against property, peaks 60–85 % LTV, back to 0 at 100 % |

`global = clamp(0, 100, 0.40·safety + 0.60·allocation − cryptoPenalty + leverageBonus)`

**When `monthlyEssentialExpenses` is null the safety net is unrated, not zero.** `score.global`
becomes the allocation score plus the modifiers and the response carries `safetyNet.known: false`.
Scoring someone 0/100 because they have not filled in a form is a lie, and it is the kind that
makes people stop believing the page.

### Essential-expense estimate

There is **no transfer marker in the schema** — `TransactionType` runs
DEPOSIT/WITHDRAWAL/BUY/SELL/DIVIDEND/FEE and `category` is free text only the Finary importer
fills — so internal movements are inferred, and every rule leans towards *over*-estimating: an
over-sized emergency fund costs a little yield, an under-sized one is the failure the figure
exists to prevent.

1. **Window** — the last six *complete* months. Excluding the current month is not optional: a
   partial month drags the mean down by up to a sixth, exactly the direction that under-sizes.
2. **Scope** — `CHECKING` accounts only, each month's total weighted by the member's share so a
   couple's joint account is not counted twice.
3. **Counterparty matching**, the only reliable rule — a debit is internal when a credit of the
   same amount landed on another readable account within ±3 days. Real double-entry matching,
   so it catches a livret top-up or a PEA funding whatever the wording. A matched credit is
   **consumed**, so one salary cannot excuse six debits that share its amount.
4. **Investment legs** — `BUY`/`SELL`/`DEPOSIT`, or any row with a ticker.
5. **Label heuristic**, explicitly last and explicitly weak, only when matching found nothing.

`estimate = Σ kept debits / monthsObserved` — divided by the months **observed**, never by six.

The figure is offered, never stored: accepting it is a `PUT`.

### Key files

Backend:
- `backend/src/main/java/com/picsou/model/WealthTier.java` — the tiers and the exhaustive mapping
- `backend/src/main/java/com/picsou/model/AccountType.java` — `ASSURANCE_VIE`, `SCPI`, `isInvestment()`
- `backend/src/main/java/com/picsou/model/MemberAllocationProfile.java` — targets; absence means defaults
- `backend/src/main/java/com/picsou/model/HoldingClassification.java` — the user's override
- `backend/src/main/java/com/picsou/service/WealthPyramidService.java` — classification and scoring
- `backend/src/main/java/com/picsou/service/AllocationTargetService.java` — read/replace, owns the defaults
- `backend/src/main/java/com/picsou/service/EssentialExpenseEstimator.java` — the transaction-derived estimate
- `backend/src/main/java/com/picsou/controller/AnalysisController.java` — `/api/analysis`
- `backend/src/main/resources/db/migration/V80__account_type_life_insurance_and_scpi.sql`
- `backend/src/main/resources/db/migration/V81__wealth_allocation.sql`

Frontend:
- `frontend/src/pages/analysis/AnalysisPage.tsx`, `PyramidSection.tsx`, `AllocationTargetsModal.tsx`
- `frontend/src/features/analysis/{api,hooks}.ts`
- `frontend/src/components/layout/sidebar-nav-items.ts`, `MobileBottomNav.tsx`
- `frontend/src/lib/constants.ts` — `HOLDING_ACCOUNT_TYPES`, `analysis` stale time
- `frontend/src/demo/data/analysis.ts`

### Flow

```
GET /api/analysis/pyramid
  └─ WealthPyramidService.pyramid(memberId)
       ├─ accessResolver.readableAccounts + sharesFor          (the dashboard's contract)
       ├─ per account, skipping LOAN:
       │    ├─ no holdings → valuation().liveEur() × share → account's tier
       │    └─ holdings    → per line: override ▸ CoinGecko ▸ account tier
       │                     + residual (envelope cash, unpriced lines) → account tier
       ├─ RealEstateSummaryService.summarize → subtract mortgage debt from REAL_ESTATE
       └─ assemble(): safety target → allocatable → tier vector → half-L1 → score
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Assets only, not net worth | The dashboard's rule already; counting property net of its mortgage would penalise exactly the leverage the pyramid wants maximised — LTV is surfaced beside the tier instead | Net-worth-based tiers |
| Absolute safety-net target | Six months of rent does not scale with the portfolio | A percentage target like the other four |
| Cushion excess in the vector at target 0 | Makes a cash-heavy portfolio score badly instead of scoring its remainder perfectly; keeps the five shares summing to 100 | Excluding the excess from the base |
| Half-L1 divergence | The only measure with a plain-language meaning: the fraction of wealth in the wrong place | RMSE (uninterpretable); χ² (explodes when a target is small — 10 % crypto held at 0 % would dominate) |
| Unrated rather than 0 when expenses are unknown | A form the user has not filled in is not a portfolio failing | Treating null as zero coverage |
| Crypto penalty scaled by crypto weight | A 2 %-crypto sleeve of small caps is a rounding error in the wealth, not a 10-point failure | A flat penalty |
| Non-monotonic leverage bonus | 100 % LTV is not a better position than 60 % | A bonus that keeps rising with LTV |
| Static top-ten coin list | CoinGecko's `/coins/markets` works, but that free tier is hostile enough that `CoinGeckoPriceProvider` carries a 429 cooldown for it — a score that flips between two page loads is worse than one slightly stale | A live market-cap ranking on the read path |
| Column-per-tier targets table | Lets `sum = 100` be a database CHECK; a CHECK cannot aggregate across rows | A normalised `(member, tier, pct)` table |
| Override keyed `(member, ticker)`, own table | The sync paths delete `account_holding` rows a provider stops reporting, so an override stored there would evaporate at the first transient gap | A `wealth_tier` column on `account_holding` |
| `ASSURANCE_VIE` is an investment type | No connector syncs one, so manual line entry is the only route to instrument-level detail — without it, all life insurance would be invisible to PR2's sector breakdown | Treating it as a balance |
| `SCPI` is **not** an investment type | SCPI shares have no Yahoo ticker; per-line valuation would take the "held but unpriced" branch and report the account as worth zero | Symmetry with `ASSURANCE_VIE` |
| `VARCHAR` + `CHECK` for the new enums | Only V1/V3/V20 create native PG enums; everything since V24 uses this. It also sidesteps the `ALTERNATE TYPE … ADD VALUE` transaction restriction | `CREATE TYPE`, which `docs/conventions/database.md` still prescribes — see Gotchas |

## Gotchas / Pitfalls

- **`docs/conventions/database.md` and the code disagree about enums.** The doc prescribes
  `CREATE TYPE … AS ENUM` + `@JdbcTypeCode(NAMED_ENUM)`; in practice only `account_type`,
  `requisition_status` and `sharing_level` (all pre-V21) are native, and every enum since uses
  `VARCHAR(n)` + a named `ck_` CHECK. This feature follows the **code**. Correcting the doc is a
  separate, deliberate commit — moving a convention inside the diff that deviates from it is what
  `CODING_RULES.md` §0 forbids.
- **`ALTER TYPE … ADD VALUE` must stand alone in its migration.** PostgreSQL refuses to *use* a
  new enum value in the transaction that added it, so V80 references neither `ASSURANCE_VIE` nor
  `SCPI`. Same reason V69 and V79 are one-liners.
- **`AccountAccessResolver.weigh(amount, null)` returns zero, not the amount.** The real
  `sharesFor` returns an explicit `100` for a wholly-owned account. A test fixture that puts
  `null` in the share map silently zeroes every figure — which is exactly how
  `WealthPyramidServiceTest` failed first.
- **`SMALLINT` maps to `Short`, not `Integer`**, or `SchemaMappingValidationTest` fails. That test
  is **Docker-gated**: without a Docker socket it skips, so an entity/DDL mismatch is a green
  local run and a red CI.
- **`cryptoTopTenShare` is null when no crypto line was seen.** An exchange tracked as a single
  balance has no per-coin detail, and scoring that silence as "holds no majors" would punish a
  portfolio for a breakdown the connector never sent.
- **The residual can be negative** when the lines are worth more than the account's own
  valuation (a provider EUR total that disagrees with per-line prices). It is added as-is rather
  than clamped, so the tier total still reconciles with `totalAssetsEur`.
- **Reading targets never writes a row.** "Never configured" has to stay distinguishable from
  "configured to today's defaults", or a future change to the defaults could never reach anyone
  who never expressed a preference. Same design as `account_ownership`, where no row means 100 %.
- **The 422 for unbalanced targets keys on `summingToOneHundred`**, a derived property, not on a
  field name — cross-field validation on a record has nowhere else to attach. The form maps its
  message off that key, so renaming the method is a breaking change.
- **`HOLDING_ACCOUNT_TYPES` was triplicated** across `AccountsPage`, `AccountDetailPage` and
  `features/accounts/hooks.ts`. It now lives in `lib/constants.ts`; adding a position-bearing type
  means one edit, not three.
- **The mobile bottom bar lost its centre logo.** Five routes left no honest way to keep the
  2-logo-2 symmetry. Its height is unchanged (`size-10` + `py-3` + `bottom-4` = 80 px), so
  `AppLayout`'s `pb-20` still matches. `frontend/src/assets/picsou_logo_white.svg` is now unused.

## Tests

- `WealthTierTest` — every `AccountType` maps; the family placements that carry a decision
- `AccountTypeTest` — `ASSURANCE_VIE` holds positions, `SCPI` does not
- `WealthPyramidServiceTest` (19) — classification incl. the crypto-in-a-CTO case and the manual
  override, envelope cash residual, shares applied once, property net of debt, the three
  safety-net regimes, the excess entering the vector, half-L1 on a known vector, crypto penalty
  scaled by weight, a balance-only crypto account drawing none, the leverage curve at 30/60/85/100
- `EssentialExpenseEstimatorTest` (14) — current month excluded, divisor is months observed,
  counterparty matching and credit consumption, investment legs, label rules firing last and not
  on ordinary wording, share weighting, null rather than zero without history
- `AllocationTargetServiceTest` — defaults without an insert, create, update, clearing expenses
- `AllocationTargetsRequestTest` — pins that `@AssertTrue` on a *record* is picked up at all
- `AnalysisControllerTest` — the member id comes from `UserContext`
- `AnalysisPage.test.tsx`, `PyramidSection.test.tsx`, `e2e/analysis.spec.ts`

## Links

- Related: [Accounts overview](./accounts-overview.md) — `ASSET_FILTER_MAP`, the other grouping
- Related: [Ownership shares](./account-ownership-shares.md) — the weighting contract
- Related: [Real estate valuation](./real-estate-valuation.md) — where `loanToValue` comes from
- Related: [Navigation](./sidebar-navigation.md) — the fifth entry and the mobile bar
