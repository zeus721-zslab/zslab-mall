import type { AdminClaimListQuery, AdminClaimListResponse } from '#layers/admin/app/types/admin-claim'
import { toAdminClaimApiParams } from '#layers/admin/app/lib/admin-claim-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 클레임 목록 API(FE-28·Track 80 D-169 BE). useAdminApi(admin_token Bearer·401 처리) 경유이며 상태(로딩·에러)는 호출부가
 * 소유한다. 승인·거부 단건 호출은 useAdminOrders(approveClaim·rejectClaim)를 그대로 쓴다(주문 상세와 동일 경로).
 */
export function useAdminClaims() {
  const api = useAdminApi()

  function list(query: AdminClaimListQuery): Promise<AdminClaimListResponse> {
    return api<AdminClaimListResponse>('/v1/admin/claims', { query: toAdminClaimApiParams(query) })
  }

  return { list }
}
