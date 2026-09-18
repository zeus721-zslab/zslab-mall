import type { AdminOrderStatsResponse } from '#layers/admin/app/types/admin-order-stats'
import type { AdminSalesStatsApiParams } from '#layers/admin/app/lib/admin-sales-stats-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

const ORDERS_PATH = '/v1/admin/stats/orders'

/** 관리자 주문·클레임 통계 API(FE-35·D-182·useAdminSalesStats 패턴). 단일 응답이라 호출 1개. 상태(로딩·에러)는 페이지가 소유한다. */
export function useAdminOrderStats() {
  const api = useAdminApi()

  function orders(params: AdminSalesStatsApiParams): Promise<AdminOrderStatsResponse> {
    return api<AdminOrderStatsResponse>(ORDERS_PATH, { params })
  }

  return { orders }
}
