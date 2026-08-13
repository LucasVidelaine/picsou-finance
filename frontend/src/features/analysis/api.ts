import { api } from '@/lib/api-client'
import type {
  AllocationTargets,
  AllocationTargetsRequest,
  Diversification,
  EssentialExpenseEstimate,
  WealthPyramid,
} from '@/types/api'

export const analysisApi = {
  pyramid: () => api.get<WealthPyramid>('/analysis/pyramid').then(r => r.data),

  diversification: () =>
    api.get<Diversification>('/analysis/diversification').then(r => r.data),

  targets: () => api.get<AllocationTargets>('/analysis/allocation-targets').then(r => r.data),

  saveTargets: (body: AllocationTargetsRequest) =>
    api.put<AllocationTargets>('/analysis/allocation-targets', body).then(r => r.data),

  expenseEstimate: () =>
    api.get<EssentialExpenseEstimate>('/analysis/essential-expenses/estimate').then(r => r.data),
}
