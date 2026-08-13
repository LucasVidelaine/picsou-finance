import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { analysisApi } from './api'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import type { AllocationTargetsRequest } from '@/types/api'

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
