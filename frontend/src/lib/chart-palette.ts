/**
 * Solid, theme-stable colours for proportional bars.
 *
 * Shared so the sector breakdown of one ETF and the sector breakdown of a whole portfolio use
 * the same visual language — two palettes would read as two unrelated charts of the same thing.
 */
export const SLICE_PALETTE = [
  'bg-sky-500',
  'bg-violet-500',
  'bg-emerald-500',
  'bg-amber-500',
  'bg-rose-500',
  'bg-teal-500',
  'bg-indigo-500',
  'bg-fuchsia-500',
  'bg-lime-500',
  'bg-orange-500',
] as const

/** The remainder a top-N breakdown does not name. */
export const OTHERS_SLICE_COLOR = 'bg-muted-foreground/30'
