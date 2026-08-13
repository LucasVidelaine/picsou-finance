# Feature: Portfolio diversification (sector + geography)

> Last updated: 2026-08-13

## Context

The holding detail modal already showed one ETF's countries and sectors. The Analysis section
answers the question that actually matters: across the **whole** equity sleeve — several funds,
several directly held shares, several accounts — how concentrated is it?

Two scores come out of it, one per axis, each with the share of the portfolio it could actually
measure stated beside it.

## How it works

### Where sector and country come from

| Holding | Sector | Country |
|---|---|---|
| ETF | look-through slices from `BoursoramaCompositionProvider` | look-through slices, same source |
| Directly held share | **Yahoo `/v1/finance/search`** | **ISIN prefix**, read from the Boursorama quote page |
| Anything the user disagrees with | `holding_classification.sector_key` | `holding_classification.country_key` |

The equity source was not the obvious one. Boursorama's company page carries
`fv_secteur_activite`, which looks like the answer and is not: it reads `n-d` for anything
outside Euronext (verified on AAPL) and, where present, gives a **sub-industry** ("Chimie de
base") that never merges with the eleven-value taxonomy the ETF slices use. Yahoo's search
endpoint — **already called** by `searchSymbols` for ISIN verification — returns that taxonomy
verbatim for US and European listings alike, so normalising is a lowercase and a
space-to-underscore, and **every resulting key already had a translation**. PR2 added no sector
keys to the locale files.

The country still comes from Boursorama, because Yahoo exposes only the listing venue — wrong for
a Paris-listed US company or an NYSE ADR. The page's analytics block carries
`"fv_code_isin":"US0378331005_AAPL"`, and an ISIN's first two characters are its ISO country of
issuance. That also recovers an identifier ingestion throws away: holdings store a Yahoo ticker,
never the ISIN they were converted from.

The two providers are merged **field by field**, not first-answer-wins. That differs from
`resolveComposition` deliberately, and has to: no single source has both halves, so taking the
first provider's whole answer would discard the country every time the sector arrived first.

### Aggregation

For each account in the `EQUITY` tier, every priced line, share-weighted exactly once (the
`DashboardService` contract). The same ticker held in two accounts is **one** position.

Per ticker, in order: manual override → ETF look-through → single-share profile → unclassified.

An ETF's published percentages are **renormalised to what the provider actually published**. A
top-ten country list does not sum to 100, so treating the percentages as absolute would dump the
remainder into "unclassified" for every fund on the page. Coverage is measured at ticker level,
which is the level the user can act on.

### The scores

Effective number of positions — the inverse Herfindahl index:

```
N_eff = 1 / Σ wᵢ²          score = min(100, 100 · N_eff / target)
```

Targets: **6** sectors, **3** regions.

Counting buckets cannot distinguish 20/20/20/20/20 from 96/1/1/1/1; both hold five sectors and
only one is diversified. `N_eff` reads them as 5.0 and 1.09.

Both scores are computed over the **classified** part, and `coveragePercent` travels with them so
a bar computed over 60% of a portfolio cannot be read as one computed over all of it — the same
discipline as the `Others` remainder in the holding modal and `Valuation.anyPriced`.

### Never fetching on the read path

`security_profile` is a durable, global cache; `SchedulerService.refreshSecurityProfiles()` warms
it weekly. The breakdown reads rows and nothing else. See the
[ADR](../decisions/2026-08-13-persisted-security-profiles.md).

### Key files

- `backend/src/main/java/com/picsou/port/EquityProfileProvider.java` — the port, and `dto/EquityProfile.java`
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` — `fetch()`, `sectorFrom()`, `sectorKey()`
- `backend/src/main/java/com/picsou/adapter/BoursoramaEquityProfileProvider.java` — country from the ISIN
- `backend/src/main/java/com/picsou/adapter/BoursoramaClient.java` — symbol resolution, shared with the composition provider
- `backend/src/main/java/com/picsou/service/SecurityProfileService.java` — `load` (read) vs `refresh` (network)
- `backend/src/main/java/com/picsou/service/PortfolioDiversificationService.java` — the roll-up
- `backend/src/main/java/com/picsou/service/HoldingClassificationService.java` — the manual override
- `backend/src/main/resources/db/migration/V82__security_profile.sql`
- `frontend/src/pages/analysis/DiversificationSection.tsx`, `frontend/src/lib/chart-palette.ts`

### Flow

```
GET /api/analysis/diversification
  └─ PortfolioDiversificationService
       ├─ readable EQUITY accounts → lines → weigh once → value by ticker
       ├─ SecurityProfileService.load(tickers)          one query, no network
       └─ per ticker: override ▸ ETF slices (renormalised) ▸ share profile ▸ unclassified
            └─ N_eff per axis, coverage + pendingTickers reported

SchedulerService.refreshSecurityProfiles()  (Sundays 03:45, ≤40 tickers)
  └─ SecurityProfileService.refresh(ticker)
       ├─ SecurityInsightService.getInsight → assetType (+ ETF composition)
       └─ if STOCK: EquityProfileProviders, merged field by field
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Yahoo search for the sector | Already-called endpoint, works outside Euronext, returns the exact taxonomy the ETF slices use — zero new i18n keys | Boursorama's `fv_secteur_activite`: `n-d` off Euronext, and a sub-industry elsewhere |
| ISIN prefix for the country | Universal, and recovers the ISIN ingestion discards | Yahoo's `exchDisp` — the listing venue, wrong for an ADR or a cross-listing |
| A separate `EquityProfileProvider` port | An ETF has a distribution, a share has one sector; and `resolveComposition` stops at the first provider with any data, so adding to that list would change ETF behaviour | Widening `EtfCompositionProvider` |
| Field-by-field merge | No source has both halves | First provider with data wins, as compositions do |
| Renormalise a partial look-through | A top-ten list does not sum to 100; treating it as absolute would mark every fund partly unclassified | Absolute percentages |
| Inverse Herfindahl | Distinguishes 20/20/20/20/20 from 96/1/1/1/1, which counting buckets cannot | Number of distinct sectors |
| Coverage stated, never renormalised | A score over 60% of a portfolio must not read as a score over all of it | Renormalising to the classified part silently |
| Override keyed `(member, ticker)` | Survives the prune that deletes `account_holding` rows a provider stops reporting | A column on `account_holding` |
| Palette extracted to `lib/chart-palette.ts` | One ETF's sectors and the whole portfolio's should read as the same chart at two scales | A second palette in the new component |

## Gotchas / Pitfalls

- **A wrong Boursorama symbol does not 404.** It answers 200 with something else, so
  `countryOf` refuses any page that does not carry `"fv_symb_societe":"<the symbol asked for>"`.
  "The page loaded" is not "the page is about this security".
- **`fv_secteur_activite` can be the literal string `n-d`.** Treat it as absent; it is not a
  sector label. This provider ignores the field entirely for that reason.
- **Never `quotes[0]` from Yahoo's search.** It is a relevance ranking: a thin European listing
  can be outranked by a better-known foreign namesake, and the position would be filed under
  that company's sector. Match the symbol exactly.
- **The country breakdown mixes two quantities** once a directly held share contributes — index
  exposure for funds, domicile for shares. `basis` says which, and the UI notes it. See the
  [ADR](../decisions/2026-08-13-equity-domicile-vs-etf-exposure.md).
- **`coveragePercent` is the more generous of the two axes.** A holding counts as classified when
  *either* sector or country could be placed; the per-axis truth is in each `Breakdown`.
- **A duplicate label from a provider loses the line, not the security.** The unique key is
  `(profile, kind, label)`, so `SecurityProfileService` de-duplicates before saving rather than
  letting one repeated slice fail the whole save.
- **The weekly job is capped at 40 tickers.** A large portfolio takes a few passes to cover. That
  is visible as `pendingTickers`, not as a wrong number.
- **`BoursoramaCompositionProvider` no longer owns its WebClient**; it takes `BoursoramaClient`.
  Its existing test only exercises the static parsers, which is why the refactor was safe — keep
  it that way.

## Tests

- `YahooEquityProfileTest` — taxonomy normalisation, exact-symbol matching (not `quotes[0]`),
  an ETF yielding no sector, null/empty responses
- `BoursoramaEquityProfileProviderTest` — real (trimmed) fixtures: a French listing, a US one
  whose sector reads `n-d`, a wrong-symbol page that must be refused, a malformed ISIN
- `SecurityProfileServiceTest` — the field-by-field merge, ETF stored as slices, duplicate labels,
  UNKNOWN still recorded, only-stale refresh with the batch cap, one bad ticker not aborting
- `PortfolioDiversificationServiceTest` (13) — share vs ETF placement, partial look-through
  renormalised, override per field, unclassified reported not renormalised, same ticker twice,
  shares applied once, `N_eff` on concentrated vs even portfolios, `EXPOSURE` vs `MIXED`
- `DiversificationSection.test.tsx`, `e2e/analysis.spec.ts`

## Links

- ADR: [Domicile vs exposure](../decisions/2026-08-13-equity-domicile-vs-etf-exposure.md)
- ADR: [Persisted security profiles](../decisions/2026-08-13-persisted-security-profiles.md)
- Related: [Security Insight](./security-insight.md) — the per-ETF composition this builds on
- Related: [Wealth pyramid](./wealth-pyramid.md) — the same Analysis page, and the same override row
