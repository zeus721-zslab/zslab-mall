import type { AdminMemberStatsResponse } from '#layers/admin/app/types/admin-member-stats'
import type { AdminSalesStatsApiParams } from '#layers/admin/app/lib/admin-sales-stats-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

const MEMBERS_PATH = '/v1/admin/stats/members'

/** 관리자 회원 통계 API(FE-35·D-182·useAdminSalesStats 패턴). 단일 응답이라 호출 1개. 상태(로딩·에러)는 페이지가 소유한다. */
export function useAdminMemberStats() {
  const api = useAdminApi()

  function members(params: AdminSalesStatsApiParams): Promise<AdminMemberStatsResponse> {
    return api<AdminMemberStatsResponse>(MEMBERS_PATH, { params })
  }

  return { members }
}
