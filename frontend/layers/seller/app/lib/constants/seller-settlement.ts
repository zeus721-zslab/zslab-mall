import type { SellerSemantic } from '#layers/seller/app/lib/constants/semantic'

/**
 * 셀러 정산 상수 단일 소스(Track 90-B-3·관리자 constants/admin-settlement 복제·CLAUDE.md 4층위 enum 잠금 (4)프론트). BE D-179 계약(SettlementStatus·
 * SettlementItemType)과 1:1. 셀러 API는 CONFIRMED·PAID만 내리지만(PENDING은 404로 숨김·D-191 ε) enum은 BE 3값 전부 잠근다.
 */

/** BE SettlementStatus 3값(PENDING → CONFIRMED → PAID 직진). */
export type SellerSettlementStatus = 'PENDING' | 'CONFIRMED' | 'PAID'

export const SELLER_SETTLEMENT_STATUS_LABEL: Record<SellerSettlementStatus, string> = {
  PENDING: '확정 전',
  CONFIRMED: '확정',
  PAID: '지급완료',
}

export const SELLER_SETTLEMENT_STATUS_SEMANTIC: Record<SellerSettlementStatus, SellerSemantic> = {
  PENDING: 'warning',
  CONFIRMED: 'info',
  PAID: 'success',
}

/** BE SettlementItemType 2값(settlement_item.item_type ENUM). 상세 품목 탭과 1:1. */
export type SellerSettlementItemType = 'SALE' | 'REFUND'

export const SELLER_SETTLEMENT_ITEM_TABS: { value: SellerSettlementItemType; label: string }[] = [
  { value: 'SALE', label: '판매' },
  { value: 'REFUND', label: '환불' },
]
export const DEFAULT_SELLER_SETTLEMENT_ITEM_TAB: SellerSettlementItemType = 'SALE'

export const SELLER_SETTLEMENT_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_SELLER_SETTLEMENT_PAGE_SIZE = 20

/** 수수료율 단위: BE는 basis-point(1000 = 10.00%). */
export const COMMISSION_RATE_BASIS_POINT_DIVISOR = 100
