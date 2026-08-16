import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AllocationTrajectory } from './AllocationTrajectory'
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from '@/components/ui/chart'
import { useProjection } from '@/features/analysis/hooks'
import { localeFromLanguage } from '@/lib/utils'
import { cn } from '@/lib/utils'
import type { ProjectionScenario } from '@/types/api'

const HORIZONS = [10, 20, 30] as const

/** Axis ticks over decades run to seven figures; the full currency format would not fit. */
function formatCompactEur(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: 'EUR',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value)
}

/**
 * The theme's chart tokens are a single-hue ramp, not five distinct hues — which suits ordered
 * data exactly. Spread across it darkest-to-lightest so "more optimistic" reads as "brighter",
 * skipping chart-5 because it disappears against a dark background at a 2px stroke.
 */
const SCENARIO_COLORS: Record<ProjectionScenario['key'], string> = {
  PESSIMISTIC: 'var(--chart-4)',
  CAUTIOUS: 'var(--chart-3)',
  REFERENCE: 'var(--chart-2)',
  OPTIMISTIC: 'var(--chart-1)',
}

/** Contributions are drawn beneath every scenario, so they read as the floor the gain sits on. */
const CONTRIBUTED_COLOR = 'var(--color-muted-foreground)'

export function ProjectionSection() {
  const { t, i18n } = useTranslation()
  const [years, setYears] = useState<number>(20)
  // Two questions, one card: how much, and in what. Tabs rather than two cards because they
  // share a horizon — switching view must not lose it.
  const [view, setView] = useState<'wealth' | 'allocation'>('wealth')
  const projection = useProjection(years)
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  const config = useMemo<ChartConfig>(() => {
    const entries = (projection.data?.scenarios ?? []).map((s) => [
      s.key,
      {
        // The rate comes from the payload, never restated here: a label that disagrees with the
        // curve that produced it is worse than no label.
        label: t(`analysis.projection.scenarios.${s.key}`, {
          value: s.annualPercent.toFixed(1),
        }),
        color: SCENARIO_COLORS[s.key],
      },
    ])
    return Object.fromEntries(entries) as ChartConfig
  }, [projection.data, t])

  // Recharts wants one row per x value with a column per series.
  const rows = useMemo(() => {
    const scenarios = projection.data?.scenarios ?? []
    const first = scenarios[0]
    if (!first) return []
    return first.points.map((point, i) => {
      // The same for every scenario — it is capital in, not a return — so it is read off the
      // first and drawn once. The gap above it is the gain, which is the figure this chart was
      // computing all along and never showed.
      const row: Record<string, string | number> = {
        date: point.date,
        contributed: point.contributedEur,
      }
      for (const scenario of scenarios) {
        row[scenario.key] = scenario.points[i]?.valueEur ?? 0
      }
      return row
    })
  }, [projection.data])

  const hasSomethingToProject =
    (projection.data?.baseValueEur ?? 0) > 0 || (projection.data?.monthlyInflowEur ?? 0) > 0

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <CardTitle>{t('analysis.projection.title')}</CardTitle>
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex gap-1">
            {(['wealth', 'allocation'] as const).map((key) => (
              <Button
                key={key}
                variant={view === key ? 'default' : 'outline'}
                size="sm"
                aria-pressed={view === key}
                onClick={() => setView(key)}
              >
                {t(`analysis.projection.views.${key}`)}
              </Button>
            ))}
          </div>
          <div className="flex gap-1">
          {HORIZONS.map((horizon) => (
            <Button
              key={horizon}
              variant={horizon === years ? 'default' : 'outline'}
              size="sm"
              onClick={() => setYears(horizon)}
            >
              {t('analysis.projection.years', { count: horizon })}
            </Button>
          ))}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {!projection.data ? null : !hasSomethingToProject ? (
          <p className="text-sm text-muted-foreground">{t('analysis.projection.nothingToProject')}</p>
        ) : (
          <>
            {/* The base is stated because it is not the net worth on the dashboard: property,
                loans and alternative assets are out, and a reader has to know that. */}
            <p className="mb-4 text-sm text-muted-foreground">
              {t('analysis.projection.basis')}{' '}
              <CurrencyDisplay value={projection.data.baseValueEur} className="text-foreground" />
              {projection.data.monthlyInflowEur > 0 && (
                <>
                  {' · '}
                  {t('analysis.projection.monthly')}{' '}
                  <CurrencyDisplay
                    value={projection.data.monthlyInflowEur}
                    className="text-foreground"
                  />
                </>
              )}
            </p>

            {view === 'allocation' ? (
              <AllocationTrajectory allocation={projection.data.allocation} />
            ) : (
              <>
            <ChartContainer config={config} className={cn('h-[320px] w-full')}>
              <AreaChart data={rows} margin={{ left: 4, right: 8, top: 8, bottom: 0 }}>
                <defs>
                  {Object.keys(config).map((key) => (
                    <linearGradient key={key} id={`fill-${key}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={`var(--color-${key})`} stopOpacity={0.25} />
                      <stop offset="100%" stopColor={`var(--color-${key})`} stopOpacity={0.02} />
                    </linearGradient>
                  ))}
                </defs>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis
                  dataKey="date"
                  tickLine={false}
                  axisLine={false}
                  tickMargin={8}
                  tickFormatter={(value: string) => value.slice(0, 4)}
                />
                <YAxis
                  tickLine={false}
                  axisLine={false}
                  width={70}
                  tickFormatter={(value: number) => formatCompactEur(value, locale)}
                />
                <ChartTooltip content={<ChartTooltipContent indicator="line" />} />
                <Area
                  type="monotone"
                  dataKey="contributed"
                  stroke={CONTRIBUTED_COLOR}
                  fill={CONTRIBUTED_COLOR}
                  fillOpacity={0.12}
                  strokeWidth={1}
                  strokeDasharray="4 3"
                  dot={false}
                  name={t('analysis.projection.contributed')}
                />
                {Object.keys(config).map((key) => (
                  <Area
                    key={key}
                    type="monotone"
                    dataKey={key}
                    stroke={`var(--color-${key})`}
                    fill={`url(#fill-${key})`}
                    strokeWidth={2}
                    dot={false}
                  />
                ))}
              </AreaChart>
            </ChartContainer>

            {/* Rendered here rather than through Recharts' <Legend>, which sorts alphabetically
                and so scrambles a series whose whole meaning is its order. */}
            <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1">
              <li className="flex items-center gap-1.5 text-xs">
                <span
                  className="size-2 shrink-0 rounded-full"
                  style={{ backgroundColor: CONTRIBUTED_COLOR }}
                  aria-hidden="true"
                />
                <span className="text-muted-foreground">
                  {t('analysis.projection.contributed')}
                </span>
              </li>
              {(projection.data.scenarios ?? []).map((scenario) => (
                <li key={scenario.key} className="flex items-center gap-1.5 text-xs">
                  <span
                    className="size-2 shrink-0 rounded-full"
                    style={{ backgroundColor: SCENARIO_COLORS[scenario.key] }}
                    aria-hidden="true"
                  />
                  <span className="text-muted-foreground">
                    {t(`analysis.projection.scenarios.${scenario.key}`, {
                      value: scenario.annualPercent.toFixed(1),
                    })}
                  </span>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs text-muted-foreground">
              {t('analysis.projection.blendNote')}
            </p>
              </>
            )}

            <p className="mt-3 text-xs text-muted-foreground">
              {t('analysis.projection.disclaimer')}
            </p>
          </>
        )}
      </CardContent>
    </Card>
  )
}
