import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'

/**
 * 관리자 정산 상수 단일 소스(Track 85 FE·CLAUDE.md 4층위 enum 잠금 (4)프론트). BE D-179 계약(SettlementStatus·SettlementItemType)과 1:1이며
 * 정산 목록·상세·셀러별 이력의 유일한 출처다.
 */

/** BE SettlementStatus 3값(settlement.status ENUM·PENDING → CONFIRMED → PAID 직진). */
export type AdminSettlementStatus = 'PENDING' | 'CONFIRMED' | 'PAID'

export const ADMIN_SETTLEMENT_STATUS_LABEL: Record<AdminSettlementStatus, string> = {
  PENDING: '대기',
  CONFIRMED: '확정',
  PAID: '지급완료',
}

export const ADMIN_SETTLEMENT_STATUS_SEMANTIC: Record<AdminSettlementStatus, AdminSemantic> = {
  PENDING: 'warning',
  CONFIRMED: 'info',
  PAID: 'success',
}

export const ADMIN_SETTLEMENT_STATUS_OPTIONS: { value: AdminSettlementStatus; title: string }[] = (
  ['PENDING', 'CONFIRMED', 'PAID'] as AdminSettlementStatus[]
).map((value) => ({ value, title: ADMIN_SETTLEMENT_STATUS_LABEL[value] }))

/** BE SettlementItemType 2값(settlement_item.item_type ENUM). 상세 품목 탭과 1:1. */
export type AdminSettlementItemType = 'SALE' | 'REFUND'

export const ADMIN_SETTLEMENT_ITEM_TABS: { value: AdminSettlementItemType; label: string }[] = [
  { value: 'SALE', label: '판매' },
  { value: 'REFUND', label: '환불' },
]
export const DEFAULT_ADMIN_SETTLEMENT_ITEM_TAB: AdminSettlementItemType = 'SALE'

/** BE 검색어 상한(AdminSettlementQueryService MAX_KEYWORD_LENGTH=50·셀러 상호 부분일치). */
export const ADMIN_SETTLEMENT_KEYWORD_MAX = 50
export const ADMIN_SETTLEMENT_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_ADMIN_SETTLEMENT_PAGE_SIZE = 20

/** 재생성 사유(BE RegenerateSettlementRequest.reason @NotBlank @Size(max = 200)). */
export const ADMIN_SETTLEMENT_REGENERATE_REASON_MAX = 200

/** 월 선택 범위: 올해 기준 과거 N년(BE MIN_YEAR 2020 이상·정산 이력이 있을 법한 범위). */
export const ADMIN_SETTLEMENT_YEAR_SPAN = 3

/** 수수료율 단위: BE는 basis-point(1000 = 10.00%). */
export const COMMISSION_RATE_BASIS_POINT_DIVISOR = 100
