/**
 * 구매자 카탈로그 목록 요약(백엔드 ProductSummaryResponse 대응). 목록 카드 렌더에 필요한 필드만 노출한다.
 * mainImageUrl은 대표 이미지 부재 시 null이며, ProductCard가 placeholder로 처리한다.
 */
export interface ProductSummary {
  productPublicId: string
  name: string
  mainImageUrl: string | null
  displayPrice: number
  soldOut: boolean
  categoryId: number
  categoryName: string
  sellerName: string
}

/**
 * 페이징 응답 봉투(백엔드 PagedResponse 대응·노출 필드 5개 한정).
 */
export interface ProductListResponse {
  items: ProductSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}
