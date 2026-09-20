import type { SellerDashboardPeriod, SellerDashboardResponse } from '#layers/seller/app/types/seller-dashboard'

/**
 * 셀러 대시보드 API(Track 90-B-3·D-192). 기간(from/to·yyyy-MM-dd·양끝 포함)만 파라미터이며 상태(로딩·에러)는 페이지가 소유한다.
 * 둘 다 생략하면 BE 기본(오늘 포함 최근 30일). 92일 초과·from>to는 BE 400(MALFORMED_REQUEST) — 화면이 먼저 막는다(seller-dashboard-view.validatePeriod).
 */
export function useSellerDashboard() {
  const api = useSellerApi()

  function get(period: SellerDashboardPeriod | null): Promise<SellerDashboardResponse> {
    return api<SellerDashboardResponse>('/v1/seller/dashboard', { query: period ? { from: period.from, to: period.to } : {} })
  }

  return { get }
}
