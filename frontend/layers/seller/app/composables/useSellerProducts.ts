import type { SellerProductDetail, SellerProductListQuery, SellerProductListResponse } from '#layers/seller/app/types/seller-product'
import { toSellerProductApiParams } from '#layers/seller/app/lib/seller-product-query'

/**
 * 셀러 상품 API 호출 모음(Track 90-C-3·90-C-1 조회 계약). 전부 useSellerApi(seller_token Bearer·401/403 SELLER_SUSPENDED 분기) 경유이며
 * 상태(로딩·에러)는 호출부(페이지)가 소유한다. detail은 수정 화면(90-C-4)이 소비하지만 계약이 같은 API라 여기서 함께 정의한다.
 */
export function useSellerProducts() {
  const api = useSellerApi()

  function list(query: SellerProductListQuery): Promise<SellerProductListResponse> {
    return api<SellerProductListResponse>('/v1/seller/products', { query: toSellerProductApiParams(query) })
  }

  /** 상세(이미지·옵션·variant·재고). 타 셀러·미존재·삭제 404(PRODUCT_NOT_FOUND)는 throw. */
  function detail(productPublicId: string): Promise<SellerProductDetail> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다.
    const path: string = `/v1/seller/products/${productPublicId}`
    return api<SellerProductDetail>(path)
  }

  return { list, detail }
}
