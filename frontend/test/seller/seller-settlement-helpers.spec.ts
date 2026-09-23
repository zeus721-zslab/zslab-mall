import { describe, it, expect } from 'vitest'
import {
  bankAccountSourceLabel,
  formatBankAccount,
  formatCommissionRate,
  formatDateOnly,
  formatSettlementPeriod,
  isNegativeNet,
  pendingSettlementNotice,
  settlementStatusLabel,
} from '#layers/seller/app/lib/seller-settlement-view'
import { SELLER_SETTLEMENT_STATUS_LABEL } from '#layers/seller/app/lib/constants/seller-settlement'

// Track 90-B-3(Track 85 셀러 API): 읽기 전용 표시 규칙 + 확정 대기(PENDING 404) 안내 문구.
describe('seller-settlement-view', () => {
  it('기간·날짜·수수료율·상태 라벨', () => {
    expect(formatSettlementPeriod('2026-07-01T00:00:00+09:00')).toBe('2026년 7월')
    expect(formatSettlementPeriod('bogus')).toBe('bogus')
    expect(formatDateOnly('2026-08-20')).toBe('2026.08.20')
    expect(formatDateOnly(undefined)).toBe('—')
    expect(formatCommissionRate(1000)).toBe('10%')
    expect(formatCommissionRate(1250)).toBe('12.5%')
    expect(settlementStatusLabel('CONFIRMED')).toBe('확정')
    expect(settlementStatusLabel('PAID')).toBe('지급완료')
    expect(SELLER_SETTLEMENT_STATUS_LABEL.PENDING).toBe('확정 대기')
  })

  it('음수 지급액·계좌 표시·출처 라벨', () => {
    expect(isNegativeNet({ netAmount: -1 })).toBe(true)
    expect(isNegativeNet({ netAmount: 0 })).toBe(false)
    expect(formatBankAccount({ id: 1, bankCode: '004', accountHolder: '홍길동', accountNumberSuffix: '1234', snapshot: true })).toBe('004 ···1234 (홍길동)')
    expect(formatBankAccount(undefined)).toBeNull()
    expect(bankAccountSourceLabel({ snapshot: true })).toBe('지급 시점 계좌(스냅샷)')
    expect(bankAccountSourceLabel({ snapshot: false })).toBe('현재 주 정산계좌')
  })

  it('pendingSettlementNotice: 0건 null · N건이면 대시보드 "정산 예정"과 같은 건수임을 설명', () => {
    expect(pendingSettlementNotice(0)).toBeNull()
    expect(pendingSettlementNotice(1)).toContain('확정 대기 정산 1건')
    expect(pendingSettlementNotice(1)).toContain('대시보드 "정산 예정"과 같은 건수')
  })
})
