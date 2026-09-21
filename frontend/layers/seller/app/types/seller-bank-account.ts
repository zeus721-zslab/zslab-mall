/**
 * 셀러 본인 정산계좌 API 타입(Track 90-D-3·FE-51·`GET/POST /api/v1/seller/bank-accounts`·backend seller/controller/response/SellerBankAccountResponse 1:1).
 * 계좌번호는 끝 4자리(accountNumberSuffix)만 온다 — 전체 번호 필드는 없다(D-188 마스킹 규칙).
 */
export type SellerBankAccountStatus = 'PENDING' | 'VERIFIED' | 'REJECTED'

export interface SellerBankAccount {
  id: number
  bankCode: string
  accountNumberSuffix: string
  accountHolder: string
  isPrimary: boolean
  status: SellerBankAccountStatus
  createdAt: string
}

/** POST 요청 본문(BE SellerBankAccountRegisterRequest). */
export interface SellerBankAccountRegisterRequest {
  bankCode: string
  accountNumber: string
  accountHolder: string
}
