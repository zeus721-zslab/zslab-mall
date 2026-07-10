import type { ClaimRequestBody, ClaimResponse } from '~/types/claim'

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
