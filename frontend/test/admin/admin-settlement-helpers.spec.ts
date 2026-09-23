import { describe, it, expect } from 'vitest'
import {
  bankAccountSourceLabel,
  canConfirm,
  canPay,
  canRegenerate,
  formatBankAccount,
  formatCommissionRate,
  formatDateOnly,
  formatSettlementPeriod,
  isNegativeNet,
  payBlockedReason,
  settlementStatusLabel,
  validateRegenerateReason,
} from '#layers/admin/app/lib/admin-settlement-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import {
  ADMIN_ORDERS_PATH,
  ADMIN_SETTLEMENTS_PATH,
  ADMIN_SETTLEMENTS_SELLERS_PATH,
  resolveBackPath,
} from '#layers/admin/app/lib/admin-back-path'

// Track 85 FE: 정산 표시 포맷(기간·날짜·수수료율)·상태별 액션 활성 판정·지급 차단 사유·재생성 사유 검증·계좌 표시·에러 문구 6코드·정산 경로 back 허용.
const BASE = { status: 'CONFIRMED' as const, netAmount: 10_000, bankAccountRegistered: true }

describe('표시 포맷', () => {
  it('기간 라벨은 periodStart(KST ISO)에서 연·월만·앞자리 0 제거', () => {
    expect(formatSettlementPeriod('2026-06-01T00:00:00+09:00')).toBe('2026년 6월')
    expect(formatSettlementPeriod('2026-12-01T00:00:00+09:00')).toBe('2026년 12월')
    expect(formatSettlementPeriod('bad')).toBe('bad')
  })

  it('LocalDate → yyyy.MM.dd·없음은 —', () => {
    expect(formatDateOnly('2026-07-20')).toBe('2026.07.20')
    expect(formatDateOnly(undefined)).toBe('—')
    expect(formatDateOnly(null)).toBe('—')
  })

  it('수수료율 basis-point → %(불필요한 소수 제거·최대 2자리)', () => {
    expect(formatCommissionRate(1000)).toBe('10%')
    expect(formatCommissionRate(1250)).toBe('12.5%')
    expect(formatCommissionRate(1234)).toBe('12.34%')
    expect(formatCommissionRate(0)).toBe('0%')
    expect(formatCommissionRate(10_000)).toBe('100%')
  })

  it('상태 라벨 3종', () => {
    expect(settlementStatusLabel('PENDING')).toBe('확정 대기')
    expect(settlementStatusLabel('CONFIRMED')).toBe('확정')
    expect(settlementStatusLabel('PAID')).toBe('지급완료')
  })

  it('계좌 표시·출처 라벨(스냅샷/현재)', () => {
    expect(formatBankAccount({ id: 1, bankCode: '004', accountHolder: '홍길동', accountNumberSuffix: '1234', snapshot: true })).toBe('004 ···1234 (홍길동)')
    expect(formatBankAccount(undefined)).toBeNull()
    expect(bankAccountSourceLabel({ snapshot: true })).toBe('지급 시점 계좌(스냅샷)')
    expect(bankAccountSourceLabel({ snapshot: false })).toBe('현재 주 정산계좌')
  })
})

describe('상태별 액션 활성', () => {
  it('PENDING → 확정·재생성만 / CONFIRMED → 지급완료만 / PAID → 없음', () => {
    const pending = { ...BASE, status: 'PENDING' as const }
    const paid = { ...BASE, status: 'PAID' as const }
    expect([canConfirm(pending), canRegenerate(pending), canPay(pending)]).toEqual([true, true, false])
    expect([canConfirm(BASE), canRegenerate(BASE), canPay(BASE)]).toEqual([false, false, true])
    expect([canConfirm(paid), canRegenerate(paid), canPay(paid)]).toEqual([false, false, false])
  })

  it('지급완료는 음수·계좌 미등록이면 비활성 + 사유(음수 우선·BE 검사 순서)', () => {
    const negative = { ...BASE, netAmount: -1 }
    const noAccount = { ...BASE, bankAccountRegistered: false }
    expect(isNegativeNet(negative)).toBe(true)
    expect(canPay(negative)).toBe(false)
    expect(payBlockedReason(negative)).toContain('음수')
    expect(canPay(noAccount)).toBe(false)
    expect(payBlockedReason(noAccount)).toContain('정산계좌')
    expect(payBlockedReason({ ...negative, bankAccountRegistered: false })).toContain('음수')
    expect(payBlockedReason(BASE)).toBeNull()
    expect(payBlockedReason({ ...negative, status: 'PENDING' })).toBeNull()
  })
})

describe('validateRegenerateReason', () => {
  it('빈 값·공백은 필수 오류·200자 초과 오류·정상은 null', () => {
    expect(validateRegenerateReason('')).toBe('재생성 사유를 입력하세요.')
    expect(validateRegenerateReason('   ')).toBe('재생성 사유를 입력하세요.')
    expect(validateRegenerateReason('a'.repeat(201))).toContain('200자')
    expect(validateRegenerateReason(' 반품 누락 반영 ')).toBeNull()
    expect(validateRegenerateReason('a'.repeat(200))).toBeNull()
  })
})

describe('에러 문구(정산 6코드)', () => {
  const error = (code: string) => ({ data: { code, detail: 'server detail' } })
  it('코드별 운영자 문구', () => {
    expect(toAdminErrorMessage(error('SETTLEMENT_NOT_FOUND'))).toBe('정산을 찾을 수 없습니다.')
    expect(toAdminErrorMessage(error('SETTLEMENT_PERIOD_INVALID'))).toContain('마감되지 않은 월')
    expect(toAdminErrorMessage(error('SETTLEMENT_ALREADY_EXISTS'))).toContain('다른 정산 작업과 겹쳤습니다')
    expect(toAdminErrorMessage(error('SETTLEMENT_INVALID_STATE'))).toContain('허용되지 않는 처리')
    expect(toAdminErrorMessage(error('SETTLEMENT_NET_NEGATIVE'))).toContain('음수')
    expect(toAdminErrorMessage(error('SETTLEMENT_BANK_ACCOUNT_MISSING'))).toContain('정산계좌')
  })
})

describe('resolveBackPath — 정산 경로', () => {
  it('정산 base: 정산 내역(쿼리 포함)·셀러별 정산 back 허용·그 외는 정산 내역 기본', () => {
    expect(resolveBackPath('/admin/settlements?year=2026&month=6&status=PENDING', ADMIN_SETTLEMENTS_PATH)).toBe('/admin/settlements?year=2026&month=6&status=PENDING')
    expect(resolveBackPath(`${ADMIN_SETTLEMENTS_SELLERS_PATH}?seller=slr_x`, ADMIN_SETTLEMENTS_PATH)).toBe(`${ADMIN_SETTLEMENTS_SELLERS_PATH}?seller=slr_x`)
    expect(resolveBackPath('/admin/orders', ADMIN_SETTLEMENTS_PATH)).toBe(ADMIN_SETTLEMENTS_PATH)
    expect(resolveBackPath('https://evil.example', ADMIN_SETTLEMENTS_PATH)).toBe(ADMIN_SETTLEMENTS_PATH)
  })

  it('주문 base: 정산 상세(/admin/settlements/12?tab=REFUND)에서 진입한 back을 허용·기존 목록 동작 불변', () => {
    expect(resolveBackPath('/admin/settlements/12?tab=REFUND&back=%2Fadmin%2Fsettlements', ADMIN_ORDERS_PATH)).toBe('/admin/settlements/12?tab=REFUND&back=%2Fadmin%2Fsettlements')
    expect(resolveBackPath('/admin/members/usr_1?tab=orders', ADMIN_ORDERS_PATH)).toBe('/admin/members/usr_1?tab=orders')
    expect(resolveBackPath('/admin/products', ADMIN_ORDERS_PATH)).toBe(ADMIN_ORDERS_PATH)
  })
})
