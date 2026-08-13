import { useTranslation } from 'react-i18next'
import { Info } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { OTHERS_SLICE_COLOR, SLICE_PALETTE } from '@/lib/chart-palette'
import { cn } from '@/lib/utils'
import type { Diversification, DiversificationBreakdown } from '@/types/api'

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

export function DiversificationSection({ data }: { data: Diversification }) {
  const { t } = useTranslation()

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
              {data.pendingTickers.length > 0 && (
                <span>
                  {t('analysis.diversification.pending', {
                    tickers: data.pendingTickers.slice(0, 5).join(', '),
                  })}
                </span>
              )}
            </div>

            {data.countries.basis === 'MIXED' && (
              <p className="flex gap-2 rounded-lg bg-muted p-3 text-xs text-muted-foreground">
                <Info className="mt-px size-4 shrink-0" aria-hidden="true" />
                {t('analysis.diversification.basisNote')}
              </p>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
