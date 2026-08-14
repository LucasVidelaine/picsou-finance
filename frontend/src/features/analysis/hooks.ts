import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { analysisApi } from './api'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import type { AllocationTargetsRequest, HoldingClassificationRequest } from '@/types/api'

export function useWealthPyramid() {
  return useQuery({
    queryKey: ['analysis', 'pyramid'],
    queryFn: analysisApi.pyramid,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useDiversification() {
  return useQuery({
    queryKey: ['analysis', 'diversification'],
    queryFn: analysisApi.diversification,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useAllocationTargets() {
  return useQuery({
    queryKey: ['analysis', 'targets'],
    queryFn: analysisApi.targets,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

/**
 * Only fetched when the targets dialog is open: it scans six months of transactions, and the
 * suggestion is meaningless until someone is looking at the field it fills.
 */
export function useEssentialExpenseEstimate(enabled: boolean) {
  return useQuery({
    queryKey: ['analysis', 'expense-estimate'],
    queryFn: analysisApi.expenseEstimate,
    enabled,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useSaveAllocationTargets() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AllocationTargetsRequest) => analysisApi.saveTargets(body),
    // The pyramid is scored against these targets, so it is stale the moment they change.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['analysis'] }),
  })
}

/**
 * The classification in force for one ticker. Only fetched while the editor is open — it is a
 * per-ticker round trip and nothing shows it until someone asks to change something.
 */
export function useHoldingClassification(
  accountId: number | null,
  ticker: string | null,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ['analysis', 'classification', accountId, ticker],
    queryFn: () => analysisApi.holdingClassification(accountId!, ticker!),
    enabled: enabled && accountId != null && !!ticker,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useClassifyHolding() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      accountId,
      ticker,
      body,
    }: {
      accountId: number
      ticker: string
      body: HoldingClassificationRequest
    }) => analysisApi.classifyHolding(accountId, ticker, body),
    // Both breakdowns and the pyramid read the override, so the whole namespace is stale.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['analysis'] }),
  })
}

/**
 * Warms the profiles now instead of waiting for the weekly pass.
 *
 * <p>Nothing is invalidated on success: the server answers 202 the moment the work is queued, so
 * the data is not ready yet and refetching here would just re-read the same empty table. The
 * caller refetches when it decides enough time has passed.
 */
export function useRefreshSecurityProfiles() {
  return useMutation({
    mutationFn: () => analysisApi.refreshSecurityProfiles(),
  })
}
