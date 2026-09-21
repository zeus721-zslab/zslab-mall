import type { SellerSalesBreakdownResponse, SellerSalesStatsResponse } from '#layers/seller/app/types/seller-stats'
import type { SellerSalesBreakdownApiParams, SellerSalesStatsApiParams } from '#layers/seller/app/lib/seller-stats-query'
import { csvFileNameFrom } from '~/lib/stats-view'

const SALES_PATH = '/v1/seller/stats/sales'
const BREAKDOWN_PATH = `${SALES_PATH}/breakdown`
const EXPORT_PATH = `${SALES_PATH}/export`

export interface SellerCsvDownload {
  blob: Blob
  fileName: string
}

/**
 * 셀러 매출 통계 API(Track 90-E-1·D-200·관리자 useAdminSalesStats 복제). 요약·추이(sales)와 분해(breakdown)는 별도 호출이라 축만 바뀌면
 * breakdown만 다시 부른다. CSV(export)는 Bearer 헤더가 필요해 직링크 대신 blob으로 받고 파일명은 Content-Disposition에서 추출한다.
 * 셀러는 리졸버가 식별하므로 셀러 파라미터가 없다. 상태(로딩·에러)는 페이지가 소유한다.
 */
export function useSellerSalesStats() {
  const api = useSellerApi()

  function sales(params: SellerSalesStatsApiParams): Promise<SellerSalesStatsResponse> {
    return api<SellerSalesStatsResponse>(SALES_PATH, { params })
  }

  function breakdown(params: SellerSalesBreakdownApiParams): Promise<SellerSalesBreakdownResponse> {
    return api<SellerSalesBreakdownResponse>(BREAKDOWN_PATH, { params })
  }

  async function breakdownCsv(params: SellerSalesBreakdownApiParams): Promise<SellerCsvDownload> {
    const response = await api.raw<Blob>(EXPORT_PATH, { params, responseType: 'blob' })
    if (!(response._data instanceof Blob)) throw new Error('CSV 응답 본문이 비어 있습니다.')
    return { blob: response._data, fileName: csvFileNameFrom(response.headers.get('content-disposition')) }
  }

  return { sales, breakdown, breakdownCsv }
}
