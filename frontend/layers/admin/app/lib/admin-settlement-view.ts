import type { AdminSettlementBankAccount, AdminSettlementSummary } from '#layers/admin/app/types/admin-settlement'
import {
  ADMIN_SETTLEMENT_REGENERATE_REASON_MAX,
  ADMIN_SETTLEMENT_STATUS_LABEL,
  COMMISSION_RATE_BASIS_POINT_DIVISOR,
  type AdminSettlementStatus,
} from '#layers/admin/app/lib/constants/admin-settlement'

/**
 * 관리자 정산 표시·판정 순수 함수(Track 85 FE·admin-member-view 패턴). 컴포넌트는 표시·배선만, 규칙은 여기서 vitest로 고정한다.
 */

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

export function settlementStatusLabel(status: AdminSettlementStatus): string {
  return ADMIN_SETTLEMENT_STATUS_LABEL[status] ?? status
}

/** 지급액 음수 여부(목록·상세 강조 + 지급 차단 사유). */
export function isNegativeNet(settlement: Pick<AdminSettlementSummary, 'netAmount'>): boolean {
  return settlement.netAmount < 0
}

/** 상태별 액션 활성: PENDING → 확정·재생성 / CONFIRMED → 지급완료 / PAID → 없음. */
export function canConfirm(settlement: Pick<AdminSettlementSummary, 'status'>): boolean {
  return settlement.status === 'PENDING'
}

export function canRegenerate(settlement: Pick<AdminSettlementSummary, 'status'>): boolean {
  return settlement.status === 'PENDING'
}

export function canPay(settlement: Pick<AdminSettlementSummary, 'status' | 'netAmount' | 'bankAccountRegistered'>): boolean {
  return settlement.status === 'CONFIRMED' && !isNegativeNet(settlement) && settlement.bankAccountRegistered
}

/**
 * 지급완료 버튼 비활성 사유(CONFIRMED인데 지급할 수 없는 경우만). BE pay 검사 순서(음수 → 계좌)와 같게 음수를 먼저 안내한다.
 * 지급 가능하거나 CONFIRMED가 아니면 null.
 */
export function payBlockedReason(
  settlement: Pick<AdminSettlementSummary, 'status' | 'netAmount' | 'bankAccountRegistered'>,
): string | null {
  if (settlement.status !== 'CONFIRMED') return null
  if (isNegativeNet(settlement)) return '지급액이 음수라 지급할 수 없습니다(차감 이월 필요).'
  if (!settlement.bankAccountRegistered) return '셀러의 주 정산계좌가 없어 지급할 수 없습니다.'
  return null
}

/** 재생성 사유 검증(BE RegenerateSettlementRequest: @NotBlank·≤200). */
export function validateRegenerateReason(reason: string): string | null {
  const trimmed = reason.trim()
  if (trimmed === '') return '재생성 사유를 입력하세요.'
  if (trimmed.length > ADMIN_SETTLEMENT_REGENERATE_REASON_MAX) {
    return `사유는 ${ADMIN_SETTLEMENT_REGENERATE_REASON_MAX}자 이하여야 합니다.`
  }
  return null
}

/** 계좌 표시: "004 ···1234 (홍길동)". 계좌 없음은 null(호출부가 안내 문구). */
export function formatBankAccount(account: AdminSettlementBankAccount | null | undefined): string | null {
  if (!account) return null
  return `${account.bankCode} ···${account.accountNumberSuffix} (${account.accountHolder})`
}

/** 계좌 출처 라벨: 지급 시점 스냅샷 vs 셀러의 현재 주계좌(STL-3·D-179 결정 7). */
export function bankAccountSourceLabel(account: Pick<AdminSettlementBankAccount, 'snapshot'>): string {
  return account.snapshot ? '지급 시점 계좌(스냅샷)' : '현재 주 정산계좌'
}
