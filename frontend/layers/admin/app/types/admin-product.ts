import type { AdminProductSort, AdminProductStatus } from '#layers/admin/app/lib/constants/product'

/** 관리자 상품 목록 행(BE AdminProductSummaryResponse 대응·Track 76). nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional. */
export interface AdminProductSummary {
  productPublicId: string
  name: string
  thumbnailUrl?: string
  sellerPublicId?: string
  sellerName?: string
  categoryId: number
  categoryName?: string
  stockTotal: number
  status: AdminProductStatus
  /** 판정 결과(재고·수동품절 종합·ProductPurchasePolicy.isSoldOut). */
  soldOut: boolean
  /** 상품 단위 수동 품절 스위치 값. */
  soldOutManual: boolean
  basePrice: number
  supplyPrice?: number
  saleStartAt?: string
  saleEndAt?: string
  createdAt: string
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface AdminProductListResponse {
  items: AdminProductSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 목록 화면 상태 = URL query 단일 소스. */
export interface AdminProductListQuery {
  keyword: string
  status: AdminProductStatus | null
  soldOut: boolean | null
  sellerPublicId: string | null
  categoryId: number | null
  sort: AdminProductSort
  page: number
  size: number
}

/** BE GET /admin/products 쿼리 파라미터(null·빈 값은 제외). */
export type AdminProductApiParams = Record<string, string | number | boolean>

/** 일괄 변경 결과(BE AdminProductBulkResponse). */
export interface AdminProductBulkResponse {
  results: AdminProductBulkResultItem[]
  successCount: number
  failureCount: number
}

export interface AdminProductBulkResultItem {
  productPublicId: string
  success: boolean
  code?: string
  message?: string
}

/** 상태 전이 응답(BE ProductApprovalResponse). */
export interface AdminProductStatusResponse {
  productPublicId: string
  status: AdminProductStatus
}

/** 셀러 선택 목록 항목(BE SellerSummaryResponse). */
export interface AdminSellerSummary {
  sellerPublicId: string
  companyName: string
  status: 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED'
}
