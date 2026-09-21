import type { SellerBankAccount, SellerBankAccountStatus } from '#layers/seller/app/types/seller-bank-account'
import { ACCOUNT_HOLDER_MAX, ACCOUNT_NUMBER_MAX, ACCOUNT_NUMBER_MIN, ACCOUNT_NUMBER_PATTERN, bankLabel } from '~/lib/constants/bank'

/**
 * 셀러 정산계좌 화면 헬퍼(Track 90-D-3·FE-51·순수 함수·vitest 대상). 폼 검증 규칙은 BE SellerBankAccountRegisterRequest(@NotBlank·계좌번호
 * 숫자·하이픈 6~30·예금주 50)의 미러인 공용 bank.ts 상수를 쓴다. 관리자 admin-seller-bank-view.ts와 같은 규칙이지만 셀러 레이어는 admin
 * import가 금지라 여기 둔다(no-admin-import.spec).
 */
export const SELLER_BANK_ACCOUNT_STATUS_LABEL: Record<SellerBankAccountStatus, string> = {
  PENDING: '확인 대기',
  VERIFIED: '인증 완료',
  REJECTED: '거부',
}

export const SELLER_BANK_ACCOUNT_MESSAGES = {
  /** 폼 상단 안내 1줄(첫 계좌 주 계좌·변경은 운영자). */
  formNotice: '첫 번째로 등록한 계좌가 주 정산계좌로 지정됩니다. 계좌 변경·수정은 운영자에게 문의하세요.',
  /** SELLER_OWNER가 아닌 역할의 조회 전용 안내. */
  readOnlyNotice: '정산계좌 등록은 셀러 대표(OWNER)만 할 수 있습니다. 조회만 가능합니다.',
  emptyTitle: '등록된 정산계좌가 없습니다',
  emptyMessage: '정산 지급을 받으려면 주 정산계좌가 필요합니다.',
  registered: '정산계좌를 등록했습니다.',
  bankCodeRequired: '은행을 선택하세요.',
  accountNumberPattern: '계좌번호는 숫자와 하이픈(-)만 입력할 수 있습니다.',
  accountNumberLength: `계좌번호는 ${ACCOUNT_NUMBER_MIN}~${ACCOUNT_NUMBER_MAX}자여야 합니다.`,
  accountHolderRequired: '예금주를 입력하세요.',
  accountHolderLength: `예금주는 ${ACCOUNT_HOLDER_MAX}자 이하여야 합니다.`,
} as const

export interface SellerBankAccountFormInput {
  bankCode: string
  accountNumber: string
  accountHolder: string
}

/** 필드별 첫 오류 메시지. 오류가 없으면 빈 객체(seller-password-form.ts validate 관례). 계좌번호·예금주는 BE와 같이 trim 후 판정한다. */
export function validateSellerBankAccountForm(input: SellerBankAccountFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (input.bankCode.trim() === '') errors.bankCode = SELLER_BANK_ACCOUNT_MESSAGES.bankCodeRequired
  const accountNumber = input.accountNumber.trim()
  if (!ACCOUNT_NUMBER_PATTERN.test(accountNumber)) {
    errors.accountNumber = SELLER_BANK_ACCOUNT_MESSAGES.accountNumberPattern
  } else if (accountNumber.length < ACCOUNT_NUMBER_MIN || accountNumber.length > ACCOUNT_NUMBER_MAX) {
    errors.accountNumber = SELLER_BANK_ACCOUNT_MESSAGES.accountNumberLength
  }
  const accountHolder = input.accountHolder.trim()
  if (accountHolder === '') errors.accountHolder = SELLER_BANK_ACCOUNT_MESSAGES.accountHolderRequired
  else if (accountHolder.length > ACCOUNT_HOLDER_MAX) errors.accountHolder = SELLER_BANK_ACCOUNT_MESSAGES.accountHolderLength
  return errors
}

/** 목록 표기: `은행명 ···끝4자리`(정산 상세 formatBankAccount와 같은 마스킹 형식·은행은 코드 대신 표시명). */
export function formatSellerBankAccount(account: Pick<SellerBankAccount, 'bankCode' | 'accountNumberSuffix'>): string {
  return `${bankLabel(account.bankCode)} ···${account.accountNumberSuffix}`
}
