import type { PagedResponse } from '~/types/order'
import type { ClaimDetail, ClaimRequestBody, ClaimResponse, ClaimSummary } from '~/types/claim'

/** 목록 기본 페이지 크기(BE BuyerClaimController list 기본 size=20 정합). */
const DEFAULT_PAGE_SIZE = 20

/**
 * API base 이원화(useOrders와 동일): SSR은 내부 직결(apiInternalBase), 브라우저는 동일 Origin 상대경로.
 */
function resolveApiBase(): string {
  const config = useRuntimeConfig()
  return import.meta.server ? `${config.apiInternalBase}/api` : config.public.apiBase || '/api'
}

/**
 * 구매자 클레임 목록 조회(GET /api/v1/claims?page&size·BUYER 전용·FE-14 유스케이스 B). useOrderList 패턴 정합:
 * Bearer 주입·page는 Ref로 받아 변경 시 useFetch가 재조회한다. 401 등 인증 실패는 error로 노출해 소비 페이지가 /login으로 유도한다.
 */
export function useClaimList(page: Ref<number>, size: number = DEFAULT_PAGE_SIZE) {
  const auth = useAuthStore()
  return useFetch<PagedResponse<ClaimSummary>>('/v1/claims', {
    key: 'claim-list',
    baseURL: resolveApiBase(),
    query: { page, size },
    headers: { Authorization: `Bearer ${auth.token}` },
  })
}

/**
 * 구매자 클레임 단건 조회(GET /api/v1/claims/{claimPublicId}·BUYER 전용). useOrderDetail 패턴 정합: Bearer 주입,
 * 미존재·타인 클레임은 BE가 404(존재 은닉)를 반환한다. 클레임은 상태 전이(승인·완료·거절)를 추적하는 화면이라
 * 재방문 시 항상 재검증한다(getCachedData로 stale 캐시 반환 차단·useOrderDetail와 동일 사유).
 */
export function useClaimDetail(claimPublicId: string) {
  const auth = useAuthStore()
  return useFetch<ClaimDetail>(`/v1/claims/${claimPublicId}`, {
    key: `claim-detail:${claimPublicId}`,
    baseURL: resolveApiBase(),
    headers: { Authorization: `Bearer ${auth.token}` },
    getCachedData: () => undefined,
  })
}

/**
 * 구매자 클레임 요청(POST /api/v1/claims·BUYER 전용·FE-14 유스케이스 A). useCheckout.submit 패턴 정합:
 * $fetch.raw로 201 + Location: /api/v1/claims/{claimPublicId}를 캡처해 clm id를 추출 반환한다.
 * 실패(401/404/422/400)는 throw해 호출부(폼 try/catch)가 타입별로 처리한다(.catch(()=>{}) 금지).
 */
export function useClaim() {
  const config = useRuntimeConfig()
  const auth = useAuthStore()

  async function requestClaim(body: ClaimRequestBody): Promise<{ claimPublicId: string }> {
    // API base 이원화(useCheckout·useOrders와 동일): SSR 내부 직결, 브라우저 동일 Origin 상대경로.
    const baseURL = import.meta.server
      ? `${config.apiInternalBase}/api`
      : config.public.apiBase || '/api'

    const response = await $fetch.raw<ClaimResponse>('/v1/claims', {
      baseURL,
      method: 'POST',
      headers: { Authorization: `Bearer ${auth.token}` },
      body,
    })

    // Location: /api/v1/claims/{claimPublicId} 마지막 세그먼트가 clm id(응답 본문 publicId와 동일). 헤더 부재 시 본문 폴백.
    const location = response.headers.get('Location')
    const claimPublicId = location?.split('/').pop() ?? response._data?.publicId
    if (!claimPublicId) {
      throw new Error('클레임 식별자를 확인할 수 없습니다.')
    }
    return { claimPublicId }
  }

  return { requestClaim }
}
