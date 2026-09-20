import type { SellerClaimDetail, SellerClaimListQuery, SellerClaimListResponse } from '#layers/seller/app/types/seller-claim'
import { toSellerClaimApiParams } from '#layers/seller/app/lib/seller-claim-query'

/**
 * 셀러 클레임 API 호출 모음(Track 90-D-1·조회 전용). 전부 useSellerApi(seller_token Bearer·401/403 SELLER_SUSPENDED 분기) 경유이며
 * 상태(로딩·에러)는 호출부(페이지)가 소유한다. 처리(승인·거부·검수)는 셀러 화면에 없다 — 관리자 전용.
 */
export function useSellerClaims() {
  const api = useSellerApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useSellerOrders 선례).
  function list(query: SellerClaimListQuery): Promise<SellerClaimListResponse> {
    return api<SellerClaimListResponse>('/v1/seller/claims', { query: toSellerClaimApiParams(query) })
  }

  /** 상세. 타 셀러·미존재는 404 CLAIM_NOT_FOUND throw. */
  function detail(claimPublicId: string): Promise<SellerClaimDetail> {
    const path: string = `/v1/seller/claims/${claimPublicId}`
    return api<SellerClaimDetail>(path)
  }

  return { list, detail }
}
