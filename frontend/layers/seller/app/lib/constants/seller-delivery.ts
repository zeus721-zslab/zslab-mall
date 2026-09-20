import type { SellerSemantic } from '#layers/seller/app/lib/constants/semantic'
import type { ClaimType } from '~/lib/constants/claim'
import { DEFAULT_SELLER_ORDER_PAGE_SIZE, SELLER_ORDER_PAGE_SIZES, type SellerDeliveryStatus } from '#layers/seller/app/lib/constants/seller-order'

/**
 * 셀러 배송 상수 단일 소스(Track 90-B-3·관리자 constants/admin-delivery 복제·CLAUDE.md 4층위 enum 잠금 (4)프론트). 배송 상태·택배사 라벨/색은
 * seller-order.ts의 상수를 그대로 쓰고, 배송 화면에만 있는 조회 범위(scope·BE AdminDeliveryScope 재사용·D-191)·방향·정렬만 여기서 정의한다.
 */

/** BE AdminDeliveryScope 4값(셀러 API도 같은 enum·D-191). ORIGINAL=원 발송(기본)·CLAIM_OUTBOUND=교환품 발송·재발송·RETURN=반품·교환 회수. */
export type SellerDeliveryScope = 'ORIGINAL' | 'CLAIM_OUTBOUND' | 'RETURN' | 'ALL'

export const SELLER_DELIVERY_SCOPE_LABEL: Record<SellerDeliveryScope, string> = {
  ORIGINAL: '원 발송',
  CLAIM_OUTBOUND: '교환·재발송',
  RETURN: '반품·교환 회수',
  ALL: '전체',
}

export const SELLER_DELIVERY_SCOPE_OPTIONS: { value: SellerDeliveryScope; title: string }[] = (
  ['ORIGINAL', 'CLAIM_OUTBOUND', 'RETURN', 'ALL'] as SellerDeliveryScope[]
).map((value) => ({ value, title: SELLER_DELIVERY_SCOPE_LABEL[value] }))

export const DEFAULT_SELLER_DELIVERY_SCOPE: SellerDeliveryScope = 'ORIGINAL'

/** BE DeliveryDirection 2값. */
export type SellerDeliveryDirection = 'OUTBOUND' | 'RETURN'

export const SELLER_DELIVERY_DIRECTION_LABEL: Record<SellerDeliveryDirection, string> = {
  OUTBOUND: '발송',
  RETURN: '회수',
}

export const SELLER_DELIVERY_DIRECTION_SEMANTIC: Record<SellerDeliveryDirection, SellerSemantic> = {
  OUTBOUND: 'info',
  RETURN: 'warning',
}

/** 클레임 연계 배지 라벨(방향 × 클레임 유형). 원 발송은 배지 없음. */
export const SELLER_DELIVERY_CLAIM_LABEL: Record<SellerDeliveryDirection, Record<ClaimType, string>> = {
  OUTBOUND: { CANCEL: '클레임 발송', RETURN: '재발송', EXCHANGE: '교환품 발송' },
  RETURN: { CANCEL: '회수', RETURN: '반품 회수', EXCHANGE: '교환 회수' },
}

/** BE AdminDeliverySort(발송일 기준·셀러 API 재사용). */
export type SellerDeliverySort = 'LATEST' | 'OLDEST'

export const SELLER_DELIVERY_SORT_OPTIONS: { value: SellerDeliverySort; title: string }[] = [
  { value: 'LATEST', title: '발송 최신순' },
  { value: 'OLDEST', title: '발송 오래된순' },
]

export const DEFAULT_SELLER_DELIVERY_SORT: SellerDeliverySort = 'LATEST'

export const SELLER_DELIVERY_PAGE_SIZES: number[] = SELLER_ORDER_PAGE_SIZES
export const DEFAULT_SELLER_DELIVERY_PAGE_SIZE = DEFAULT_SELLER_ORDER_PAGE_SIZE

/** 검색어 최대 길이(BE SellerDeliveryQueryService MAX_KEYWORD_LENGTH). */
export const SELLER_DELIVERY_KEYWORD_MAX = 50
/** 송장 정정 사유 최대 길이(BE SellerDeliveryTrackingCorrectionRequest @Size(max=200)). */
export const SELLER_DELIVERY_CORRECTION_REASON_MAX = 200

/** 송장 정정·배송완료가 허용되는 배송 상태(BE Delivery.correctTracking·markDelivered·SHIPPING만). */
export const SELLER_DELIVERY_ACTIONABLE_STATUSES: readonly SellerDeliveryStatus[] = ['SHIPPING']
