import type { SellerProductStatsResponse } from '#layers/seller/app/types/seller-product-stats'
import type { SellerProductStatsApiParams } from '#layers/seller/app/lib/seller-stats-query'

const PRODUCTS_PATH = '/v1/seller/stats/products'

/** 셀러 상품 통계 API(Track 90-E-3·D-200). 단일 응답·기간(from·to)만·셀러 파라미터 없음. 상태(로딩·에러)는 페이지가 소유한다. */
export function useSellerProductStats() {
  const api = useSellerApi()

  function products(params: SellerProductStatsApiParams): Promise<SellerProductStatsResponse> {
    return api<SellerProductStatsResponse>(PRODUCTS_PATH, { params })
  }

  return { products }
}
