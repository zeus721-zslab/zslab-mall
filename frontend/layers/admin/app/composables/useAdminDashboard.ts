import type { AdminDashboardResponse } from '#layers/admin/app/types/admin-dashboard'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/** 관리자 대시보드 API(FE-33·D-180·useAdminSettlements 패턴). 파라미터 없는 단일 GET이며 상태(로딩·에러)는 페이지가 소유한다. */
export function useAdminDashboard() {
  const api = useAdminApi()

  function get(): Promise<AdminDashboardResponse> {
    return api<AdminDashboardResponse>('/v1/admin/dashboard')
  }

  return { get }
}
