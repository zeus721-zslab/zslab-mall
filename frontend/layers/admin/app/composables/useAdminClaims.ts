import type {
  AdminClaimBulkApproveResponse,
  AdminClaimDetail,
  AdminClaimListQuery,
  AdminClaimListResponse,
} from '#layers/admin/app/types/admin-claim'
import { toAdminClaimApiParams } from '#layers/admin/app/lib/admin-claim-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 클레임 목록 API(FE-28·Track 80 D-169 BE). useAdminApi(관리자 쿠키 인증·CSRF 헤더·401 처리) 경유이며 상태(로딩·에러)는 호출부가
 * 소유한다. 승인·거부 단건 호출은 useAdminOrders(approveClaim·rejectClaim)를 그대로 쓴다(주문 상세와 동일 경로).
 * 단건 조회(제안 포함)·일괄 승인은 인박스용(D-250).
 */
export function useAdminClaims() {
  const api = useAdminApi()

  function list(query: AdminClaimListQuery): Promise<AdminClaimListResponse> {
    return api<AdminClaimListResponse>('/v1/admin/claims', { query: toAdminClaimApiParams(query) })
  }

  function get(claimPublicId: string): Promise<AdminClaimDetail> {
    return api<AdminClaimDetail>(`/v1/admin/claims/${claimPublicId}`)
  }

  /** 항목별 커밋 · 승인 제안만 승인(서버가 다시 판정) · 항목 실패가 있어도 200. */
  function bulkApprove(claimPublicIds: string[]): Promise<AdminClaimBulkApproveResponse> {
    return api<AdminClaimBulkApproveResponse>('/v1/admin/claims/bulk/approve', { method: 'POST', body: { claimPublicIds } })
  }

  return { list, get, bulkApprove }
}
