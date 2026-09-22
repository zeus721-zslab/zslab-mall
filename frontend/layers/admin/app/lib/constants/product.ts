import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'

/**
 * 관리자 상품 관리 상수 단일 소스(FE-25·D-165 §8 4층위 4단). BE enum(ProductStatus·AdminProductSort·ProductImageType)과 1:1이며
 * 매직 문자열 대신 이 유니온·옵션 배열을 소비한다.
 */

/** BE ProductStatus 7값. 관리자 화면은 판매중·판매중지·판매대기 3상태를 주로 다루고 나머지는 라벨만 둔다. */
export type AdminProductStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SALE' | 'HIDDEN' | 'STOPPED'

/** BE SaleStopSource 2값(V34·D-206). STOPPED일 때만 존재. ADMIN 중지는 셀러가 재판매할 수 없고 관리자만 풀 수 있다. */
export type AdminSaleStopSource = 'ADMIN' | 'SELLER'

export const ADMIN_SALE_STOP_SOURCE_LABEL: Record<AdminSaleStopSource, string> = {
  ADMIN: '관리자 중지',
  SELLER: '셀러 중지',
}

export const ADMIN_PRODUCT_STATUS_LABEL: Record<AdminProductStatus, string> = {
  DRAFT: '임시저장',
  PENDING: '판매대기',
  APPROVED: '승인됨',
  REJECTED: '거부됨',
  SALE: '판매중',
  HIDDEN: '숨김',
  STOPPED: '판매중지',
}

/** 상태 chip 의미 색상(constants/semantic.ts): 판매중=success·판매중지=danger·판매대기=warning·그 외는 중립 info. */
export const ADMIN_PRODUCT_STATUS_SEMANTIC: Record<AdminProductStatus, AdminSemantic> = {
  DRAFT: 'info',
  PENDING: 'warning',
  APPROVED: 'info',
  REJECTED: 'danger',
  SALE: 'success',
  HIDDEN: 'info',
  STOPPED: 'danger',
}

/** 필터 드롭다운 옵션(운영에서 실제 쓰는 3상태 + 거부됨). */
export const ADMIN_PRODUCT_STATUS_OPTIONS: { value: AdminProductStatus; title: string }[] = [
  { value: 'SALE', title: ADMIN_PRODUCT_STATUS_LABEL.SALE },
  { value: 'STOPPED', title: ADMIN_PRODUCT_STATUS_LABEL.STOPPED },
  { value: 'PENDING', title: ADMIN_PRODUCT_STATUS_LABEL.PENDING },
  { value: 'REJECTED', title: ADMIN_PRODUCT_STATUS_LABEL.REJECTED },
]

/**
 * 상태 전환 메뉴 목표(BE 허용 전이·D-165: PENDING→SALE(승인)·PENDING→REJECTED·SALE→STOPPED·STOPPED→SALE). SALE→PENDING 등 불허.
 * REJECTED→PENDING(거부 철회·Track 101-A)은 사유 입력이 필요해 이 메뉴가 아니라 별도 액션(AdminProductWithdrawRejectionDialog)이 담당한다.
 * 값은 목표 상태이며 호출 경로는 useAdminProducts.changeStatus가 분기한다(PENDING→SALE=approve·REJECTED=reject·그 외 sale-status).
 */
export type AdminProductStatusTarget = 'SALE' | 'STOPPED' | 'REJECTED'

export const ADMIN_PRODUCT_ALLOWED_TRANSITIONS: Record<AdminProductStatus, AdminProductStatusTarget[]> = {
  DRAFT: [],
  PENDING: ['SALE', 'REJECTED'],
  APPROVED: [],
  REJECTED: [],
  SALE: ['STOPPED'],
  HIDDEN: [],
  STOPPED: ['SALE'],
}

/** 거부 철회 사유 입력 상한(BE AdminProductWithdrawRejectionRequest @Size(max=200) 1:1·Track 101-A). */
export const ADMIN_PRODUCT_REASON_MAX = 200

/** 상태 전환 메뉴 항목(전부 노출·허용되지 않으면 비활성). */
export const ADMIN_PRODUCT_STATUS_TARGETS: { value: AdminProductStatusTarget; title: string }[] = [
  { value: 'SALE', title: '판매중으로' },
  { value: 'STOPPED', title: '판매중지로' },
  { value: 'REJECTED', title: '거부' },
]

/** 일괄 상태 변경 목표(BE bulk/status @Pattern SALE|STOPPED). */
export type AdminProductBulkStatusTarget = 'SALE' | 'STOPPED'

export const ADMIN_PRODUCT_BULK_STATUS_OPTIONS: { value: AdminProductBulkStatusTarget; title: string }[] = [
  { value: 'SALE', title: '판매중으로 (판매대기는 승인)' },
  { value: 'STOPPED', title: '판매중지로' },
]

/** 품절 필터(판정 결과 기준·BE soldOut Boolean). */
export const ADMIN_PRODUCT_SOLD_OUT_OPTIONS: { value: boolean; title: string }[] = [
  { value: true, title: '품절' },
  { value: false, title: '재고 있음' },
]

/** 재고 필터(BE AdminProductStockFilter·Track 89-A). LOW는 대시보드 "재고 임박"(가용재고 1~5)과 같은 기준·수동품절 제외. */
export type AdminProductStockFilter = 'LOW' | 'OUT' | 'IN_STOCK'

export const ADMIN_PRODUCT_STOCK_FILTER_OPTIONS: { value: AdminProductStockFilter; title: string }[] = [
  { value: 'LOW', title: '재고 임박(1~5)' },
  { value: 'OUT', title: '재고 0' },
  { value: 'IN_STOCK', title: '재고 여유(6 이상)' },
]

/** BE AdminProductSort(PRICE는 base_price 기준). */
export type AdminProductSort = 'LATEST' | 'NAME' | 'PRICE_ASC' | 'PRICE_DESC'

export const ADMIN_PRODUCT_SORT_OPTIONS: { value: AdminProductSort; title: string }[] = [
  { value: 'LATEST', title: '최신순' },
  { value: 'NAME', title: '이름순' },
  { value: 'PRICE_ASC', title: '판매가 낮은순' },
  { value: 'PRICE_DESC', title: '판매가 높은순' },
]

export const DEFAULT_ADMIN_PRODUCT_SORT: AdminProductSort = 'LATEST'

/** 페이지 크기 옵션(BE size 1~100 클램프). */
export const ADMIN_PRODUCT_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_ADMIN_PRODUCT_PAGE_SIZE = 20

/** BE ProductImageType(V21)·FE-26 이미지 편집이 소비. GALLERY=순서·대표 대상, DETAIL=상세 영역. */
export type ProductImageType = 'GALLERY' | 'DETAIL'

export const PRODUCT_IMAGE_TYPE_LABEL: Record<ProductImageType, string> = {
  GALLERY: '갤러리',
  DETAIL: '상세',
}
