import type { SellerSettlementBankAccount, SellerSettlementSummary } from '#layers/seller/app/types/seller-settlement'
import {
  COMMISSION_RATE_BASIS_POINT_DIVISOR,
  SELLER_SETTLEMENT_STATUS_LABEL,
  type SellerSettlementStatus,
} from '#layers/seller/app/lib/constants/seller-settlement'

/** 셀러 정산 표시 순수 함수(Track 90-B-3·관리자 admin-settlement-view 복제·읽기 전용이라 액션 판정은 없다). 컴포넌트는 표시·배선만, 규칙은 여기서 vitest로 고정한다. */

/** 정산 기간 라벨: periodStart(KST ISO) → "2026년 6월". 패턴 불일치 시 원본 폴백. */
export function formatSettlementPeriod(periodStart: string): string {
  const matched = periodStart.match(/^(\d{4})-(\d{2})/)
  if (!matched) return periodStart
  return `${matched[1]}년 ${Number(matched[2])}월`
}

/** LocalDate(yyyy-MM-dd) → "yyyy.MM.dd". 없으면 "—". */
export function formatDateOnly(value: string | null | undefined): string {
  if (!value) return '—'
  const matched = value.match(/^(\d{4})-(\d{2})-(\d{2})/)
  return matched ? `${matched[1]}.${matched[2]}.${matched[3]}` : value
}

/** basis-point → 퍼센트 문자열(1000 → "10%", 1250 → "12.5%", 1234 → "12.34%"). */
export function formatCommissionRate(basisPoint: number): string {
  const percent = basisPoint / COMMISSION_RATE_BASIS_POINT_DIVISOR
  return `${Number(percent.toFixed(2))}%`
}

export function settlementStatusLabel(status: SellerSettlementStatus): string {
  return SELLER_SETTLEMENT_STATUS_LABEL[status] ?? status
}

/** 지급액 음수 여부(목록·상세 강조·차감 이월 안내). */
export function isNegativeNet(settlement: Pick<SellerSettlementSummary, 'netAmount'>): boolean {
  return settlement.netAmount < 0
}

/** 계좌 표시: "004 ···1234 (홍길동)". 계좌 없음은 null(호출부가 안내 문구). */
export function formatBankAccount(account: SellerSettlementBankAccount | null | undefined): string | null {
  if (!account) return null
  return `${account.bankCode} ···${account.accountNumberSuffix} (${account.accountHolder})`
}

/** 계좌 출처 라벨: 지급 시점 스냅샷 vs 현재 주계좌(D-179 결정 7). */
export function bankAccountSourceLabel(account: Pick<SellerSettlementBankAccount, 'snapshot'>): string {
  return account.snapshot ? '지급 시점 계좌(스냅샷)' : '현재 주 정산계좌'
}

/**
 * 확정 대기(PENDING) 정산 안내 문구. 셀러 API는 PENDING을 404로 숨기므로(D-191 ε) 목록에 없는 게 정상이며, 대시보드·상단 "정산 예정 N건"은
 * 그 확정 대기 건수다. 건수 0이면 null(안내 없음).
 */
export function pendingSettlementNotice(pendingCount: number): string | null {
  if (pendingCount <= 0) return null
  return `확정 대기 정산 ${pendingCount.toLocaleString('ko-KR')}건이 있습니다. 운영자가 확정하면 이 목록에 표시됩니다(대시보드 "정산 예정"과 같은 건수).`
}
