import type { ClaimType } from '~/lib/constants/claim'
import type { PagedResponse } from '~/types/order'
import type {
  ClaimAttachmentUploadResponse,
  ClaimDetail,
  ClaimRequestBody,
  ClaimResponse,
  ClaimShipment,
  ClaimSummary,
  ReturnShipmentBody,
} from '~/types/claim'

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
 * 구매자 클레임 목록 조회(GET /api/v1/claims?type&page&size·BUYER 전용·FE-14 유스케이스 B·FE-63 유형 탭). useOrderList 패턴 정합:
 * Bearer 주입·page·type을 Ref로 받아 변경 시 useFetch가 재조회한다. 401 등 인증 실패는 error로 노출해 소비 페이지가 /login으로 유도한다.
 *
 * <p>type이 null이면 전체 유형이다(BE는 파라미터 미전송으로 본다 — Nuxt는 null 쿼리를 보내지 않는다).
 */
export function useClaimList(page: Ref<number>, type: Ref<ClaimType | null>, size: number = DEFAULT_PAGE_SIZE) {
  const auth = useAuthStore()
  return useFetch<PagedResponse<ClaimSummary>>('/v1/claims', {
    // 주문 탭과 한 페이지에 공존하고 유형까지 갈리므로 key에 유형을 넣는다(고정 key면 탭 간 SSR 페이로드가 서로를 덮는다).
    key: computed(() => `claim-list:${type.value ?? 'ALL'}`),
    baseURL: resolveApiBase(),
    query: { type, page, size },
    headers: { Authorization: `Bearer ${auth.token}` },
    // 주문 탭으로 진입(type=null)하면 클레임을 조회하지 않는다. 이후 탭 전환은 query 변화가 재조회를 건다.
    immediate: type.value !== null,
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

  /**
   * 반품 사진 업로드(POST /api/v1/claims/attachments·multipart files[]·FE-29). 항상 200·파일별 결과(부분 실패)이며 5장 초과·files 누락 400,
   * 413(용량)은 throw해 호출부가 처리한다. 브라우저 전용(File 객체).
   */
  function uploadAttachments(files: File[]): Promise<ClaimAttachmentUploadResponse> {
    const formData = new FormData()
    for (const file of files) formData.append('files', file)
    return $fetch<ClaimAttachmentUploadResponse>('/v1/claims/attachments', {
      baseURL: config.public.apiBase || '/api',
      method: 'POST',
      headers: { Authorization: `Bearer ${auth.token}` },
      body: formData,
    })
  }

  /** 회수 송장 등록(POST /api/v1/claims/{id}/return-shipment·FE-29). 404·422·400은 throw. */
  function registerReturnShipment(claimPublicId: string, body: ReturnShipmentBody): Promise<ClaimShipment> {
    return $fetch<ClaimShipment>(`/v1/claims/${claimPublicId}/return-shipment`, {
      baseURL: resolveApiBase(),
      method: 'POST',
      headers: { Authorization: `Bearer ${auth.token}` },
      body,
    })
  }

  /**
   * 클레임 신청 취소(POST /api/v1/claims/{id}/cancel·Track 101-A). 접수(REQUESTED) 상태의 본인 요청만 취소된다.
   * 404(타인·미존재)·422(승인 이후 등 상태 위반)·401은 throw해 호출부가 타입별로 처리한다(registerReturnShipment 패턴).
   */
  function cancelClaim(claimPublicId: string): Promise<ClaimResponse> {
    return $fetch<ClaimResponse>(`/v1/claims/${claimPublicId}/cancel`, {
      baseURL: resolveApiBase(),
      method: 'POST',
      headers: { Authorization: `Bearer ${auth.token}` },
    })
  }

  return { requestClaim, uploadAttachments, registerReturnShipment, cancelClaim }
}
