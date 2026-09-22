import type {
  SellerProductSort,
  SellerProductStatus,
  SellerProductImageType,
  SellerSaleStopSource,
  SellerVariantStatus,
} from '#layers/seller/app/lib/constants/seller-product'

/**
 * 셀러 상품·재고 API 타입(Track 90-C-3·BE 계약 1:1·backend product/controller/response/SellerProduct*·inventory/.../SellerInventorySummaryResponse 실측).
 * nullable(thumbnailUrl·description·sellerSku·barcode·optionLabel·categoryName·updatedAt)은 BE NON_NULL 직렬화로 생략될 수 있어 optional.
 * 시각 문자열은 KST 오프셋 ISO(KstOffsetSerializer·예 2026-09-20T14:05:44.990+09:00)라 formatDateTime으로만 표시한다 — 관리자 상품 응답도
 * 같은 직렬화지만 관리자 파서·타입은 복제하지 않는다(FE-44 §2 격리).
 */

/** 목록 행(BE SellerProductSummaryResponse·11필드). 셀러 식별·공급가·판매기간·재고 합계는 없다(재고는 재고 화면). saleStopSource는 STOPPED일 때만(D-206). */
export interface SellerProductSummary {
  productPublicId: string
  name: string
  categoryId: number
  categoryName?: string
  status: SellerProductStatus
  saleStopSource?: SellerSaleStopSource
  basePrice: number
  thumbnailUrl?: string
  variantCount: number
  createdAt: string
  updatedAt: string
}

export interface SellerProductImage {
  imageId: number
  imageUrl: string
  imageType: SellerProductImageType
  displayOrder: number
  main: boolean
}

export interface SellerProductOptionValue {
  optionValueId: number
  value: string
  displayOrder: number
}

export interface SellerProductOptionGroup {
  optionGroupId: number
  name: string
  displayOrder: number
  values: SellerProductOptionValue[]
}

export interface SellerProductVariantOption {
  optionGroupId: number
  optionValueId: number
  value: string
}

export interface SellerProductVariant {
  variantPublicId: string
  variantCode: string
  sellerSku?: string
  barcode?: string
  additionalPrice: number
  status: SellerVariantStatus
  soldoutManual: boolean
  displayOrder: number
  options: SellerProductVariantOption[]
  quantityOnHand: number
  quantityReserved: number
  quantityAvailable: number
}

/** 상세(BE SellerProductDetailResponse·수정 화면용·90-C-4가 소비). */
export interface SellerProductDetail {
  productPublicId: string
  name: string
  description?: string
  categoryId: number
  categoryName?: string
  status: SellerProductStatus
  saleStopSource?: SellerSaleStopSource
  basePrice: number
  thumbnailUrl?: string
  soldoutManual: boolean
  createdAt: string
  updatedAt: string
  images: SellerProductImage[]
  optionGroups: SellerProductOptionGroup[]
  variants: SellerProductVariant[]
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface SellerPagedResponse<T> {
  items: T[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

export type SellerProductListResponse = SellerPagedResponse<SellerProductSummary>

/** 상품 목록 화면 상태 = URL query 단일 소스. */
export interface SellerProductListQuery {
  keyword: string
  status: SellerProductStatus | null
  categoryId: number | null
  sort: SellerProductSort
  page: number
  size: number
}

/** 재고 행(BE SellerInventorySummaryResponse·variant 축·9필드). updatedAt은 재고 행 갱신 시각. */
export interface SellerInventorySummary {
  variantPublicId: string
  productPublicId: string
  productName?: string
  optionLabel?: string
  sellerSku?: string
  quantityOnHand: number
  quantityReserved: number
  quantityAvailable: number
  updatedAt?: string
}

export type SellerInventoryListResponse = SellerPagedResponse<SellerInventorySummary>

/** 재고 화면 상태 = URL query 단일 소스. productPublicId는 상품 1건 한정(상품 목록에서 진입 시 부착). */
export interface SellerInventoryListQuery {
  keyword: string
  productPublicId: string | null
  page: number
  size: number
}

/** 입고·출고 요청(BE SellerInventoryMarkInboundRequest·MarkOutboundRequest 공통). quantity는 양수 크기. */
export interface SellerInventoryAdjustRequest {
  quantity: number
  reason: string
}

/** 입고·출고 응답(BE InventoryAdjustResponse). */
export interface SellerInventoryAdjustResponse {
  variantPublicId: string
  quantityOnHand: number
  quantityReserved: number
  quantityAvailable: number
}

/** BE GET 쿼리 파라미터(null·빈 값은 제외). */
export type SellerProductApiParams = Record<string, string | number>

/** 등록 응답(BE ProductRegistrationResponse). variantPublicIds는 요청 variants 순서와 1:1. */
export interface SellerProductRegistrationResponse {
  productPublicId: string
  variantPublicIds: string[]
}

/** 이미지 업로드 응답(BE ImageUploadResponse·항상 200·파일별 결과). 실패 항목은 code·message를 담고 url 계열은 null. */
export interface SellerImageUploadResponse {
  results: {
    fileName: string
    success: boolean
    url?: string
    thumbnailUrl?: string
    width?: number
    height?: number
    size?: number
    code?: string
    message?: string
  }[]
  successCount: number
  failureCount: number
}
