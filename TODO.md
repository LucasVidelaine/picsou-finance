# TODO

Scratch list of deferred work noticed in passing. Not durable knowledge — delete once resolved.

- 2026-07-15: `AccountsVisibilityTab.tsx` has no component test, unlike sibling `TradeRepublicTab.test.tsx`. It's mostly presentational but hinges on two boolean inversions (`checked={!account.hidden}`, `onCheckedChange={(visible) => toggle.mutate({ hidden: !visible })}`) — classic double-negation footgun, cheap to pin down with a render+toggle test. Flagged non-blocking by the final review on the account-visibility feature (docs/briefs/2026-07-14-account-visibility-toggle-plan.md).
- 2026-07-15: `ASSET_FILTER_MAP` filtering (`const types = ASSET_FILTER_MAP[filter]; if (!types) return X; return X.filter(...)`) is duplicated 3× in `frontend/src/pages/accounts/AccountsPage.tsx` after the `useAccountTree` refactor. A small `filterByAssetType(list)` helper would DRY it without over-abstracting. Flagged Minor/non-blocking by the same final review.
