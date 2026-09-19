import { describe, it, expect } from 'vitest'
import {
  bankAccountStatusLabel,
  bankLabel,
  canEditBankAccount,
  canMakePrimary,
  hasReferencedBankAccount,
  maskedAccountNumber,
  primaryBankAccount,
  primaryChangeHeadline,
  validateAccountNumberInput,
} from '#layers/admin/app/lib/admin-seller-bank-view'
import {
  ADMIN_BANK_OPTIONS,
  ADMIN_SELLER_ACCOUNT_NUMBER_MAX,
  SELLER_BANK_ACCOUNT_PRIMARY_CHANGE_NOTICE,
} from '#layers/admin/app/lib/constants/admin-seller'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import type { AdminSellerBankAccountRow } from '#layers/admin/app/types/admin-seller'

// FE-41: 셀러 정산계좌 화면 순수 함수 — 끝 4자리 표기·주 계좌 판정·전환 가능 여부·계좌번호 입력 검증·문구. 계좌 실값이 아닌 테스트 상수만 쓴다.
function row(overrides: Partial<AdminSellerBankAccountRow> = {}): AdminSellerBankAccountRow {
  return {
    id: 1, bankCode: 'SHINHAN', accountHolder: '홍길동', accountNumberSuffix: '5678', status: 'VERIFIED', verifiedAt: '2026-09-18T10:00:00',
    isPrimary: false, referencedBySettlement: false, createdAt: '2026-09-18T10:00:00', updatedAt: '2026-09-18T10:00:00',
    ...overrides,
  }
}

describe('admin-seller-bank-view', () => {
  it('끝 4자리 표기: 응답 suffix만 "····" 뒤에 붙이고 전체 번호는 다루지 않는다', () => {
    expect(maskedAccountNumber(row({ accountNumberSuffix: '0001' }))).toBe('····0001')
    expect(maskedAccountNumber(row({ accountNumberSuffix: '5678' }))).toBe('····5678')
  })

  it('은행 라벨: 옵션 코드는 표시명·없는 코드(구 시드·외부 입력)는 코드 그대로', () => {
    expect(bankLabel('KB')).toBe('KB국민은행')
    expect(bankLabel('SHINHAN')).toBe('신한은행')
    expect(bankLabel('004')).toBe('004')
    expect(new Set(ADMIN_BANK_OPTIONS.map((option) => option.value)).size).toBe(ADMIN_BANK_OPTIONS.length)
  })

  it('주 계좌 판정: isPrimary 행 1건·없으면 null / 전환 가능 = 주 계좌가 아닌 행만', () => {
    const primary = row({ id: 2, isPrimary: true })
    expect(primaryBankAccount([row({ id: 1 }), primary])).toBe(primary)
    expect(primaryBankAccount([row({ id: 1 })])).toBeNull()
    expect(primaryBankAccount([])).toBeNull()
    expect(canMakePrimary(primary)).toBe(false)
    expect(canMakePrimary(row({ id: 1 }))).toBe(true)
  })

  it('수정 가능 판정: referencedBySettlement(응답 플래그·BE 409 동일 기준)면 불가 / 카드 안내는 참조 행이 하나라도 있을 때', () => {
    expect(canEditBankAccount(row())).toBe(true)
    expect(canEditBankAccount(row({ referencedBySettlement: true }))).toBe(false)
    expect(hasReferencedBankAccount([row(), row({ id: 2 })])).toBe(false)
    expect(hasReferencedBankAccount([row(), row({ id: 2, referencedBySettlement: true })])).toBe(true)
    expect(hasReferencedBankAccount([])).toBe(false)
  })

  it('계좌번호 입력 검증: 숫자·하이픈 6~30자(BE @Pattern·@Size 동일)·공백 제거·마스킹 없음', () => {
    expect(validateAccountNumberInput('110-000-000999')).toEqual({ ok: true, normalized: '110-000-000999' })
    expect(validateAccountNumberInput(' 110 000 000999 ')).toEqual({ ok: true, normalized: '110000000999' })
    expect(validateAccountNumberInput('')).toMatchObject({ ok: false, message: '계좌번호를 입력하세요.' })
    expect(validateAccountNumberInput('12AB-3456')).toMatchObject({ ok: false, message: expect.stringContaining('숫자와 하이픈') })
    expect(validateAccountNumberInput('12345')).toMatchObject({ ok: false, message: expect.stringContaining('6~30자') })
    expect(validateAccountNumberInput('1'.repeat(ADMIN_SELLER_ACCOUNT_NUMBER_MAX + 1))).toMatchObject({ ok: false })
    expect(validateAccountNumberInput('1'.repeat(ADMIN_SELLER_ACCOUNT_NUMBER_MAX))).toMatchObject({ ok: true })
  })

  it('상태 라벨·전환 문구·안내 3문장', () => {
    expect(bankAccountStatusLabel('VERIFIED')).toBe('인증 완료')
    expect(bankAccountStatusLabel('PENDING')).toBe('인증 대기')
    expect(primaryChangeHeadline(row())).toBe('신한은행 ····5678 (홍길동) 계좌를 주 정산계좌로 지정합니다.')
    expect(SELLER_BANK_ACCOUNT_PRIMARY_CHANGE_NOTICE[0]).toBe('이후 정산 지급은 이 계좌로 이루어집니다.')
    expect(SELLER_BANK_ACCOUNT_PRIMARY_CHANGE_NOTICE[2]).toContain('이미 지급완료된 정산')
  })

  it('에러 코드 문구: 정산 참조 409·이미 주 계좌 422·미존재 404', () => {
    expect(toAdminErrorMessage({ data: { code: 'SELLER_BANK_ACCOUNT_REFERENCED' } })).toBe('정산 지급에 사용된 계좌는 수정할 수 없습니다. 새 계좌를 등록한 뒤 주 계좌로 전환하세요.')
    expect(toAdminErrorMessage({ data: { code: 'SELLER_BANK_ACCOUNT_INVALID_STATE' } })).toBe('이미 주 정산계좌입니다.')
    expect(toAdminErrorMessage({ data: { code: 'SELLER_BANK_ACCOUNT_NOT_FOUND' } })).toContain('정산계좌를 찾을 수 없습니다')
  })
})
