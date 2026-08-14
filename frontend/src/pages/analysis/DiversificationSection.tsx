import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Info, Pencil, RefreshCw } from 'lucide-react'
import { toast } from 'sonner'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { OTHERS_SLICE_COLOR, SLICE_PALETTE } from '@/lib/chart-palette'
import { cn } from '@/lib/utils'
import { useRefreshSecurityProfiles } from '@/features/analysis/hooks'
import { HoldingClassificationModal } from './HoldingClassificationModal'
import type { Diversification, DiversificationBreakdown, UnclassifiedLine } from '@/types/api'

/** Longest list shown before it is cut; the rest are reachable from the holdings table. */
const MAX_UNCLASSIFIED_SHOWN = 8

/** Below this a slice is thinner than a hairline; it joins the remainder instead. */
const MIN_VISIBLE_PERCENT = 0.5

function scoreTone(score: number): string {
  if (score >= 75) return 'text-green-600 dark:text-green-500'
  if (score >= 50) return 'text-amber-600 dark:text-amber-500'
  return 'text-red-600 dark:text-red-500'
}

/**
 * A colour-only proportional bar over a wrapping legend — the same "line" rendering the holding
 * modal uses, from the same palette, so one ETF's sectors and the whole portfolio's read as the
 * same chart at two scales.
 */
function BreakdownBar({
  breakdown,
  labelNs,
  title,
}: {
  breakdown: DiversificationBreakdown
  labelNs: string
  title: string
}) {
  const { t } = useTranslation()

  const visible = breakdown.slices.filter((s) => s.percent >= MIN_VISIBLE_PERCENT)
  const remainder = breakdown.slices
    .filter((s) => s.percent < MIN_VISIBLE_PERCENT)
    .reduce((acc, s) => acc + s.percent, 0)

  const items: { label: string; percent: number; color: string }[] = visible.map((slice, i) => ({
    // The backend sends stable keys for sectors and countries; an unmapped label falls through
    // verbatim, so the raw value is the fallback rather than the key.
    label: t(`${labelNs}.${slice.label}`, slice.label),
    percent: slice.percent,
    color: SLICE_PALETTE[i % SLICE_PALETTE.length],
  }))
  if (remainder >= MIN_VISIBLE_PERCENT) {
    items.push({
      label: t('holdings.insight.others'),
      percent: remainder,
      color: OTHERS_SLICE_COLOR,
    })
  }

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm text-foreground">{title}</span>
        <div className="flex items-baseline gap-2">
          <span className={cn('text-sm', scoreTone(breakdown.score))}>
            {breakdown.score} / 100
          </span>
          <span className="text-xs text-muted-foreground">
            {t('analysis.diversification.effective', {
              value: breakdown.effectiveCount.toFixed(1),
              target: breakdown.targetCount,
            })}
          </span>
        </div>
      </div>

      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('analysis.diversification.noData')}</p>
      ) : (
        <>
          <div className="flex h-2 w-full overflow-hidden rounded-full">
            {items.map((item, i) => (
              <div
                key={`${item.label}-${i}`}
                className={item.color}
                style={{ width: `${item.percent}%` }}
                aria-hidden="true"
              />
            ))}
          </div>
          <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
            {items.map((item, i) => (
              <li key={`${item.label}-${i}`} className="flex items-center gap-1.5 text-xs">
                <span className={cn('size-2 shrink-0 rounded-full', item.color)} aria-hidden="true" />
                <span className="text-foreground">{item.label}</span>
                <span className="text-muted-foreground">{item.percent.toFixed(1)}%</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

/**
 * The lines the breakdown could not place, each openable in the editor.
 *
 * <p>Listing them is the point. A bare "40 978 € unclassified" tells someone their score is
 * wrong without telling them what to do about it, and the securities that land here are exactly
 * the ones no provider will ever resolve — employee-savings FCPEs, unlisted funds — so waiting
 * does not help.
 */
function UnclassifiedList({
  lines,
  onEdit,
}: {
  lines: UnclassifiedLine[]
  onEdit: (line: UnclassifiedLine) => void
}) {
  const { t } = useTranslation()
  const refresh = useRefreshSecurityProfiles()

  const shown = lines.slice(0, MAX_UNCLASSIFIED_SHOWN)
  const hidden = lines.length - shown.length
  // Never looked up is a different problem from looked up and unknown: the first is fixed by a
  // refresh, the second only by hand. Offering the button when nothing is pending would promise
  // a fix that cannot come.
  const anyPending = lines.some((line) => !line.profileLooked)

  return (
    <div className="space-y-3 border-t border-border pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm text-foreground">
          {t('analysis.classification.listTitle')}
        </span>
        {anyPending && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={refresh.isPending}
            onClick={() =>
              refresh.mutate(undefined, {
                onSuccess: (result) =>
                  toast.success(
                    result.alreadyRunning
                      ? t('analysis.classification.refreshAlready')
                      : t('analysis.classification.refreshQueued', {
                          count: result.queuedTickers,
                        }),
                  ),
              })
            }
          >
            <RefreshCw
              className={cn('size-4', refresh.isPending && 'animate-spin')}
              aria-hidden="true"
            />
            {t('analysis.classification.refresh')}
          </Button>
        )}
      </div>

      <ul className="space-y-1">
        {shown.map((line) => (
          <li
            key={line.ticker}
            className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-muted px-3 py-2"
          >
            <div className="min-w-0">
              <span className="text-sm text-foreground">{line.name ?? line.ticker}</span>
              <span className="ml-2 text-xs text-muted-foreground">{line.ticker}</span>
            </div>
            <div className="flex items-center gap-3">
              <span className="text-xs text-muted-foreground">
                {line.profileLooked
                  ? t('analysis.classification.missing', {
                      fields: [
                        line.sectorMissing && t('analysis.classification.sector'),
                        line.countryMissing && t('analysis.classification.country'),
                      ]
                        .filter(Boolean)
                        .join(', '),
                    })
                  : t('analysis.classification.notLookedUp')}
              </span>
              <CurrencyDisplay value={line.valueEur} className="text-sm text-foreground" />
              {/* accountId is what authorises the write; without one there is nothing to open. */}
              {line.accountId !== null && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => onEdit(line)}
                  aria-label={t('analysis.classification.editAria', { ticker: line.ticker })}
                >
                  <Pencil className="size-4" aria-hidden="true" />
                </Button>
              )}
            </div>
          </li>
        ))}
      </ul>

      {hidden > 0 && (
        <p className="text-xs text-muted-foreground">
          {t('analysis.classification.andMore', { count: hidden })}
        </p>
      )}
    </div>
  )
}

export function DiversificationSection({ data }: { data: Diversification }) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState<UnclassifiedLine | null>(null)

  const hasHoldings = data.totalValueEur > 0

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('analysis.diversification.title')}</CardTitle>
      </CardHeader>
      <CardContent>
        {!hasHoldings ? (
          <p className="text-sm text-muted-foreground">
            {t('analysis.diversification.noHoldings')}
          </p>
        ) : (
          <div className="space-y-6">
            <BreakdownBar
              breakdown={data.sectors}
              labelNs="holdings.insight.sectorNames"
              title={t('analysis.diversification.sectors')}
            />
            <BreakdownBar
              breakdown={data.countries}
              labelNs="holdings.insight.countryNames"
              title={t('analysis.diversification.countries')}
            />

            {/* Coverage is stated, never renormalised away: a bar computed over 60% of the
                portfolio must not look like one computed over all of it. */}
            <div className="flex flex-wrap items-center gap-2 border-t border-border pt-4 text-xs text-muted-foreground">
              <span>
                {t('analysis.diversification.coverage', {
                  value: data.coveragePercent.toFixed(0),
                })}
              </span>
              {data.unclassifiedValueEur > 0 && (
                <Badge variant="outline">
                  <CurrencyDisplay value={data.unclassifiedValueEur} />{' '}
                  {t('analysis.diversification.unclassified')}
                </Badge>
              )}
            </div>

            {data.unclassified.length > 0 && (
              <UnclassifiedList lines={data.unclassified} onEdit={setEditing} />
            )}

            {data.countries.basis === 'MIXED' && (
              <p className="flex gap-2 rounded-lg bg-muted p-3 text-xs text-muted-foreground">
                <Info className="mt-px size-4 shrink-0" aria-hidden="true" />
                {t('analysis.diversification.basisNote')}
              </p>
            )}
          </div>
        )}
      </CardContent>

      <HoldingClassificationModal
        open={editing !== null}
        accountId={editing?.accountId ?? null}
        ticker={editing?.ticker ?? null}
        name={editing?.name}
        onClose={() => setEditing(null)}
      />
    </Card>
  )
}
