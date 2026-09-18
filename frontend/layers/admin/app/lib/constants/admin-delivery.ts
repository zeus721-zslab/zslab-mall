import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import type { ClaimType } from '~/lib/constants/claim'
import { ADMIN_ORDER_PAGE_SIZES, DEFAULT_ADMIN_ORDER_PAGE_SIZE, type AdminDeliveryStatus } from '#layers/admin/app/lib/constants/admin-order'

/**
 * 관리자 배송 관리 상수 단일 소스(FE-37·Track 89-B D-184·CLAUDE.md 4층위 enum 잠금 (4)프론트). 배송 상태·택배사 라벨/색은
 * admin-order.ts의 기존 상수를 그대로 쓰고, 배송 화면에만 있는 조회 범위(scope)·방향·정렬만 여기서 정의한다.
 */

/** BE AdminDeliveryScope 4값. ORIGINAL=원 발송(기본)·CLAIM_OUTBOUND=교환품 발송·재발송·RETURN=반품·교환 회수. */
export type AdminDeliveryScope = 'ORIGINAL' | 'CLAIM_OUTBOUND' | 'RETURN' | 'ALL'

export const ADMIN_DELIVERY_SCOPE_LABEL: Record<AdminDeliveryScope, string> = {
  ORIGINAL: '원 발송',
  CLAIM_OUTBOUND: '교환·재발송',
  RETURN: '반품·교환 회수',
  ALL: '전체',
}

export const ADMIN_DELIVERY_SCOPE_OPTIONS: { value: AdminDeliveryScope; title: string }[] = (
  ['ORIGINAL', 'CLAIM_OUTBOUND', 'RETURN', 'ALL'] as AdminDeliveryScope[]
).map((value) => ({ value, title: ADMIN_DELIVERY_SCOPE_LABEL[value] }))

export const DEFAULT_ADMIN_DELIVERY_SCOPE: AdminDeliveryScope = 'ORIGINAL'

/** BE DeliveryDirection 2값. */
export type AdminDeliveryDirection = 'OUTBOUND' | 'RETURN'

export const ADMIN_DELIVERY_DIRECTION_LABEL: Record<AdminDeliveryDirection, string> = {
  OUTBOUND: '발송',
  RETURN: '회수',
}

/** 방향 chip 의미 색상: 회수(RETURN)=warning(노랑 계열)·발송은 chip 없이 기본 흐름. */
export const ADMIN_DELIVERY_DIRECTION_SEMANTIC: Record<AdminDeliveryDirection, AdminSemantic> = {
  OUTBOUND: 'info',
  RETURN: 'warning',
}

/** 클레임 연계 배지 라벨(방향 × 클레임 유형). 원 발송은 배지 없음. */
export const ADMIN_DELIVERY_CLAIM_LABEL: Record<AdminDeliveryDirection, Record<ClaimType, string>> = {
  OUTBOUND: { CANCEL: '클레임 발송', RETURN: '재발송', EXCHANGE: '교환품 발송' },
  RETURN: { CANCEL: '회수', RETURN: '반품 회수', EXCHANGE: '교환 회수' },
}

/** BE AdminDeliverySort(발송일 기준). */
export type AdminDeliverySort = 'LATEST' | 'OLDEST'

export const ADMIN_DELIVERY_SORT_OPTIONS: { value: AdminDeliverySort; title: string }[] = [
  { value: 'LATEST', title: '발송 최신순' },
  { value: 'OLDEST', title: '발송 오래된순' },
]

export const DEFAULT_ADMIN_DELIVERY_SORT: AdminDeliverySort = 'LATEST'

export const ADMIN_DELIVERY_PAGE_SIZES: number[] = ADMIN_ORDER_PAGE_SIZES
export const DEFAULT_ADMIN_DELIVERY_PAGE_SIZE = DEFAULT_ADMIN_ORDER_PAGE_SIZE

/** 검색어 최대 길이(BE AdminDeliveryQueryService MAX_KEYWORD_LENGTH). */
export const ADMIN_DELIVERY_KEYWORD_MAX = 50
/** 송장 정정 사유 최대 길이(BE AdminDeliveryTrackingCorrectionRequest @Size(max=200)). */
export const ADMIN_DELIVERY_CORRECTION_REASON_MAX = 200

/** 송장 정정이 허용되는 배송 상태(BE Delivery.correctTracking·SHIPPING만). */
export const ADMIN_DELIVERY_CORRECTABLE_STATUSES: readonly AdminDeliveryStatus[] = ['SHIPPING']
