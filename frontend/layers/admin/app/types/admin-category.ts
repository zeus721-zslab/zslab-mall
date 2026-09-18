/**
 * 관리자 카테고리 관리 타입(FE-38·Track 89-C BE AdminCategoryListResponse·UpdateCategoryRequest·ReorderCategoriesRequest 대응).
 * commissionRate는 basis-point(1000 = 10.00%)·미설정이면 BE non_null 정책으로 키가 생략되므로 optional로 받는다.
 */
export interface AdminCategorySummary {
  categoryId: number
  displayName: string
  sortOrder: number
  commissionRate?: number | null
  productCount: number
  createdAt: string
}

export interface AdminCategoryListResponse {
  /** 플랫폼 기본 수수료율(bp·settlement.default-commission-rate). 카테고리 율 미설정 시 실제 적용되는 값. */
  defaultCommissionRate: number
  items: AdminCategorySummary[]
}

export interface AdminCategoryCreateRequest {
  displayName: string
  sortOrder: number
}

/** PUT 전체 치환 — 3필드 항상 전송. commissionRate null = 미설정(기본율)으로 환원. reason은 율 실변경 시 BE가 필수 검증. */
export interface AdminCategoryUpdateRequest {
  displayName: string
  sortOrder: number
  commissionRate: number | null
  reason: string | null
}

export interface AdminCategoryReorderRequest {
  categoryIds: number[]
}
