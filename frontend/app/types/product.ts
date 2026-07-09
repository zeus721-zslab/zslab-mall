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

/**
 * 구매자 카탈로그 목록 정렬 기준(백엔드 ProductCatalogSort 대응). DB 영속 컬럼이 아닌 조회 파라미터라
 * 4층위 enum 잠금 대상이 아니며(recon-report-67 B-2), 프론트 단일 소스 유니온으로 매직 문자열을 방지한다.
 */
export type ProductSort = 'LATEST' | 'PRICE_ASC' | 'PRICE_DESC' | 'NAME'

/**
 * 상세 이미지 1건(백엔드 ProductDetailResponse.Image 대응). 목록의 평면 mainImageUrl과 달리 상세는
 * 이미지 리스트를 노출하며, main 플래그로 대표 이미지를 판별한다(display_order 오름차순).
 */
export interface ProductImage {
  imageUrl: string
  displayOrder: number
  main: boolean
}

/** 옵션값(예: 검정·백엔드 ProductDetailResponse.OptionValue 대응). */
export interface ProductOptionValue {
  value: string
  displayOrder: number
}

/** 옵션 그룹(예: 색상)과 그 값 목록(백엔드 ProductDetailResponse.OptionGroup 대응·DEFAULT sentinel 제외). */
export interface ProductOptionGroup {
  name: string
  displayOrder: number
  values: ProductOptionValue[]
}

/** variant 옵션 조합의 한 축(그룹명 + 선택값·백엔드 ProductDetailResponse.Option 대응). */
export interface ProductVariantOption {
  groupName: string
  value: string
}

/**
 * 판매 variant/SKU(백엔드 ProductDetailResponse.Variant 대응). salePrice = basePrice + additionalPrice.
 * options는 해당 variant의 옵션 조합이며, 단순상품은 빈 목록이다. variantPublicId는 담기 대상키(FE-10b 소비).
 */
export interface ProductVariant {
  variantPublicId: string
  salePrice: number
  soldOut: boolean
  options: ProductVariantOption[]
}

/**
 * 구매자 카탈로그 단건 상세(백엔드 ProductDetailResponse 대응·11필드). 요약 필드에 description·images·
 * optionGroups·variants를 더한다. 목록의 mainImageUrl은 상세에 없으며 images 리스트의 main 플래그로 대표를 판별한다.
 */
export interface ProductDetail {
  productPublicId: string
  name: string
  description: string | null
  categoryId: number
  categoryName: string
  sellerName: string
  displayPrice: number
  soldOut: boolean
  images: ProductImage[]
  optionGroups: ProductOptionGroup[]
  variants: ProductVariant[]
}
