import type { SellerOrderStatsResponse } from '#layers/seller/app/types/seller-order-stats'
import type { SellerSalesStatsApiParams } from '#layers/seller/app/lib/seller-stats-query'

const ORDERS_PATH = '/v1/seller/stats/orders'

/** 셀러 주문·클레임 통계 API(Track 90-E-2·D-200·관리자 useAdminOrderStats 복제). 단일 응답이라 호출 1개·셀러 파라미터 없음. 상태(로딩·에러)는 페이지가 소유한다. */
export function useSellerOrderStats() {
  const api = useSellerApi()

  function orders(params: SellerSalesStatsApiParams): Promise<SellerOrderStatsResponse> {
    return api<SellerOrderStatsResponse>(ORDERS_PATH, { params })
  }

  return { orders }
}
