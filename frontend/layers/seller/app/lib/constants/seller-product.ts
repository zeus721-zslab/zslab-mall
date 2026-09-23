import type { SellerSemantic } from '#layers/seller/app/lib/constants/semantic'
import { PRODUCT_STATUS_LABELS, SALE_STOP_SOURCE_LABELS, VARIANT_STATUS_LABELS } from '~/lib/constants/product-status'

/**
 * 셀러 상품·재고 상수 단일 소스(Track 90-C-3·관리자 constants/product 복제·CLAUDE.md 4층위 enum 잠금 (4)프론트). BE enum(ProductStatus·
 * SellerProductSort·ProductImageType·ProductVariantStatus)과 1:1이며 매직 문자열 대신 이 유니온·옵션 배열을 소비한다.
 */

/** BE ProductStatus 7값. 셀러 화면은 승인대기·판매중·판매중지·거부됨 4상태를 주로 다루고 나머지는 라벨만 둔다(상태 변경은 관리자 소관). */
export type SellerProductStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SALE' | 'HIDDEN' | 'STOPPED'

/** 라벨 실체는 공용 단일 소스(app/lib/constants/product-status.ts·Track 102 FE-64). 셀러 코드의 기존 이름만 유지한다. */
export const SELLER_PRODUCT_STATUS_LABEL: Record<SellerProductStatus, string> = PRODUCT_STATUS_LABELS

/** 상태 chip 의미 색상: 판매중=success·판매중지/거부됨=danger·승인대기=warning·그 외 중립 info. */
export const SELLER_PRODUCT_STATUS_SEMANTIC: Record<SellerProductStatus, SellerSemantic> = {
  DRAFT: 'info',
  PENDING: 'warning',
  APPROVED: 'info',
  REJECTED: 'danger',
  SALE: 'success',
  HIDDEN: 'info',
  STOPPED: 'danger',
}

/** BE SaleStopSource 2값(V34·D-206). STOPPED일 때만 존재하며 ADMIN 중지는 셀러가 재판매할 수 없다. */
export type SellerSaleStopSource = 'ADMIN' | 'SELLER'

export const SELLER_SALE_STOP_SOURCE_LABEL: Record<SellerSaleStopSource, string> = SALE_STOP_SOURCE_LABELS

/** 셀러 판매 상태 액션(BE sale-status body status: STOP→STOPPED·RESUME→SALE). */
export type SellerSaleAction = 'STOP' | 'RESUME'

export const SELLER_SALE_ACTION_LABEL: Record<SellerSaleAction, string> = {
  STOP: '판매중지',
  RESUME: '재판매',
}

/** 필터 드롭다운 옵션(셀러 등록·관리자 전이로 실제 도달하는 4상태). */
export const SELLER_PRODUCT_STATUS_OPTIONS: { value: SellerProductStatus; title: string }[] = [
  { value: 'SALE', title: SELLER_PRODUCT_STATUS_LABEL.SALE },
  { value: 'PENDING', title: SELLER_PRODUCT_STATUS_LABEL.PENDING },
  { value: 'STOPPED', title: SELLER_PRODUCT_STATUS_LABEL.STOPPED },
  { value: 'REJECTED', title: SELLER_PRODUCT_STATUS_LABEL.REJECTED },
]

/** BE SellerProductSort 4값(PRICE는 base_price 기준). */
export type SellerProductSort = 'LATEST' | 'NAME' | 'PRICE_ASC' | 'PRICE_DESC'

export const SELLER_PRODUCT_SORT_OPTIONS: { value: SellerProductSort; title: string }[] = [
  { value: 'LATEST', title: '최신순' },
  { value: 'NAME', title: '이름순' },
  { value: 'PRICE_ASC', title: '판매가 낮은순' },
  { value: 'PRICE_DESC', title: '판매가 높은순' },
]

export const DEFAULT_SELLER_PRODUCT_SORT: SellerProductSort = 'LATEST'

/** BE ProductImageType(V21). GALLERY=순서·대표 대상, DETAIL=상세 영역. */
export type SellerProductImageType = 'GALLERY' | 'DETAIL'

/** BE ProductVariantStatus 3값. HIDDEN=셀러 비활성화(삭제 대신·90-C-2). */
export type SellerVariantStatus = 'SALE' | 'HIDDEN' | 'STOPPED'

export const SELLER_VARIANT_STATUS_LABEL: Record<SellerVariantStatus, string> = VARIANT_STATUS_LABELS

/** 페이지 크기 옵션(BE size 1~100 클램프·주문 화면과 동일 3단). */
export const SELLER_PRODUCT_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_SELLER_PRODUCT_PAGE_SIZE = 20

/** 검색어 최대 길이(BE SellerProductQueryService·SellerInventoryQueryService MAX_KEYWORD_LENGTH). */
export const SELLER_PRODUCT_KEYWORD_MAX = 50

/** 입출고 사유 최대 길이(BE SellerInventoryMark*Request.reason @Size(max=255)). */
export const SELLER_INVENTORY_REASON_MAX = 255

/** 입출고 모드(BE mark-inbound·mark-outbound). */
export type SellerInventoryAdjustMode = 'INBOUND' | 'OUTBOUND'

export const SELLER_INVENTORY_ADJUST_LABEL: Record<SellerInventoryAdjustMode, string> = {
  INBOUND: '입고',
  OUTBOUND: '출고',
}
