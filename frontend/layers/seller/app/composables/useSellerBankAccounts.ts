import type { SellerBankAccount, SellerBankAccountRegisterRequest } from '#layers/seller/app/types/seller-bank-account'

/**
 * 셀러 본인 정산계좌 API(Track 90-D-3·FE-51·D-199). 전부 useSellerApi 경유이며 상태(로딩·에러)는 호출부가 소유한다(useSellerSettlements 관례).
 * 등록은 SELLER_OWNER만(그 외 403 SELLER_OWNER_REQUIRED·SUSPENDED 403 SELLER_SUSPENDED). 수정·삭제·주 계좌 전환은 관리자 전용이라 없다.
 */
export function useSellerBankAccounts() {
  const api = useSellerApi()

  function list(): Promise<SellerBankAccount[]> {
    return api<SellerBankAccount[]>('/v1/seller/bank-accounts')
  }

  function register(body: SellerBankAccountRegisterRequest): Promise<SellerBankAccount> {
    return api<SellerBankAccount>('/v1/seller/bank-accounts', { method: 'POST', body })
  }

  return { list, register }
}
