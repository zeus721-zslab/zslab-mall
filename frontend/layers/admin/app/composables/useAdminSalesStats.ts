import type { AdminSalesBreakdownResponse, AdminSalesStatsResponse } from '#layers/admin/app/types/admin-sales-stats'
import type { AdminSalesBreakdownApiParams, AdminSalesStatsApiParams } from '#layers/admin/app/lib/admin-sales-stats-query'
import { csvFileNameFrom } from '#layers/admin/app/lib/admin-sales-stats-view'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

const SALES_PATH = '/v1/admin/stats/sales'
const BREAKDOWN_PATH = `${SALES_PATH}/breakdown`
const BREAKDOWN_CSV_PATH = `${SALES_PATH}/breakdown.csv`

export interface CsvDownload {
  blob: Blob
  fileName: string
}

/**
 * 관리자 매출 통계 API(FE-34·D-181·useAdminDashboard 패턴). 요약·추이(sales)와 분해(breakdown)는 별도 호출이라 축·드릴다운 변경 시
 * breakdown만 다시 부른다. CSV는 Bearer 헤더가 필요해 직링크 대신 blob으로 받고 파일명은 Content-Disposition에서 추출한다.
 * 상태(로딩·에러)는 페이지가 소유한다.
 */
export function useAdminSalesStats() {
  const api = useAdminApi()

  function sales(params: AdminSalesStatsApiParams): Promise<AdminSalesStatsResponse> {
    return api<AdminSalesStatsResponse>(SALES_PATH, { params })
  }

  function breakdown(params: AdminSalesBreakdownApiParams): Promise<AdminSalesBreakdownResponse> {
    return api<AdminSalesBreakdownResponse>(BREAKDOWN_PATH, { params })
  }

  async function breakdownCsv(params: AdminSalesBreakdownApiParams): Promise<CsvDownload> {
    const response = await api.raw<Blob>(BREAKDOWN_CSV_PATH, { params, responseType: 'blob' })
    if (!(response._data instanceof Blob)) throw new Error('CSV 응답 본문이 비어 있습니다.')
    return { blob: response._data, fileName: csvFileNameFrom(response.headers.get('content-disposition')) }
  }

  return { sales, breakdown, breakdownCsv }
}
