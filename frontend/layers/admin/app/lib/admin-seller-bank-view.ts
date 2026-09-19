import type { AdminSellerBankAccount, AdminSellerBankAccountRow } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_BANK_OPTIONS,
  ADMIN_SELLER_ACCOUNT_NUMBER_MAX,
  ADMIN_SELLER_ACCOUNT_NUMBER_MIN,
  ADMIN_SELLER_ACCOUNT_NUMBER_PATTERN,
  ADMIN_SELLER_BANK_ACCOUNT_STATUS_LABEL,
  type AdminSellerBankAccountStatus,
} from '#layers/admin/app/lib/constants/admin-seller'

/** 셀러 정산계좌 화면 순수 판정·문구(FE-41·D-188). 컴포넌트가 아니라 여기 두어 vitest로 고정한다. 계좌번호 전체는 어디서도 다루지 않는다. */

/** 은행 코드 → 표시명. 옵션에 없는 코드(구 시드·외부 입력)는 코드 그대로. */
export function bankLabel(bankCode: string): string {
  return ADMIN_BANK_OPTIONS.find((option) => option.value === bankCode)?.title ?? bankCode
}

/** 마스킹 표기 "····0001"(정산 상세·89-D 계좌 카드와 같은 규칙). 응답에는 끝 4자리만 오므로 화면이 잘라낼 것은 없다. */
export function maskedAccountNumber(account: Pick<AdminSellerBankAccount, 'accountNumberSuffix'>): string {
  return `····${account.accountNumberSuffix}`
}

export function bankAccountStatusLabel(status: AdminSellerBankAccountStatus): string {
  return ADMIN_SELLER_BANK_ACCOUNT_STATUS_LABEL[status]
}

/** 주 계좌 행(SLR-3·V32 UNIQUE로 최대 1건). */
export function primaryBankAccount(rows: AdminSellerBankAccountRow[]): AdminSellerBankAccountRow | null {
  return rows.find((row) => row.isPrimary) ?? null
}

/** 주 계좌 전환 가능 여부: 이미 주 계좌면 불가(BE 422 SELLER_BANK_ACCOUNT_INVALID_STATE와 같은 판정). */
export function canMakePrimary(row: Pick<AdminSellerBankAccountRow, 'isPrimary'>): boolean {
  return !row.isPrimary
}

/** 수정 가능 여부: 정산이 지급 계좌로 참조하면 불가(BE 409 SELLER_BANK_ACCOUNT_REFERENCED와 같은 판정·응답 플래그가 SoT·FE 재계산 없음). */
export function canEditBankAccount(row: Pick<AdminSellerBankAccountRow, 'referencedBySettlement'>): boolean {
  return !row.referencedBySettlement
}

/** 셀러의 계좌 중 정산 지급에 사용된 계좌가 하나라도 있는지(카드 하단 안내 노출 조건·계좌 단위 플래그 기반). */
export function hasReferencedBankAccount(rows: Pick<AdminSellerBankAccountRow, 'referencedBySettlement'>[]): boolean {
  return rows.some((row) => row.referencedBySettlement)
}

/**
 * 계좌번호 입력 검증(BE @Pattern ^[0-9-]+$·@Size 6~30과 동일). 입력 중 마스킹하지 않는다(오타 확인 필요). 공백은 제거 후 판정한다.
 * 화면 검증은 안내용이며 최종 판정은 BE 400 fieldErrors다.
 */
export function validateAccountNumberInput(value: string): { ok: true; normalized: string } | { ok: false; message: string } {
  const normalized = value.replace(/\s+/g, '')
  if (normalized === '') return { ok: false, message: '계좌번호를 입력하세요.' }
  if (!ADMIN_SELLER_ACCOUNT_NUMBER_PATTERN.test(normalized)) return { ok: false, message: '계좌번호는 숫자와 하이픈(-)만 입력할 수 있습니다.' }
  if (normalized.length < ADMIN_SELLER_ACCOUNT_NUMBER_MIN || normalized.length > ADMIN_SELLER_ACCOUNT_NUMBER_MAX) {
    return { ok: false, message: `계좌번호는 ${ADMIN_SELLER_ACCOUNT_NUMBER_MIN}~${ADMIN_SELLER_ACCOUNT_NUMBER_MAX}자여야 합니다.` }
  }
  return { ok: true, normalized }
}

/** 주 계좌 전환 확인 문구 머리글: "신한은행 ····5678 (홍길동) 계좌를 주 정산계좌로 지정합니다." */
export function primaryChangeHeadline(row: Pick<AdminSellerBankAccountRow, 'bankCode' | 'accountNumberSuffix' | 'accountHolder'>): string {
  return `${bankLabel(row.bankCode)} ${maskedAccountNumber(row)} (${row.accountHolder}) 계좌를 주 정산계좌로 지정합니다.`
}
