import { SELLER_LOGIN_PATH, SELLER_SUSPENDED_ERROR_CODE } from '#layers/seller/app/lib/constants/auth'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'

/**
 * 셀러 API 호출 래퍼(Track 90-A·관리자 useAdminApi 동형). /seller/**는 CSR 전용(ssr:false)이라 브라우저 baseURL(동일 Origin 상대경로)만 쓴다.
 * 매 요청에 셀러 세션(seller_token) Bearer를 주입하고 BE 응답을 D-190 규칙으로 분기한다:
 * - 401(토큰 만료·무효·PENDING/TERMINATED 셀러·소속 없음) → seller_token만 비운 뒤 셀러 로그인으로(auth_token·admin_token 유지).
 * - 403 SELLER_SUSPENDED(정지 셀러의 쓰기) → 세션 유지·정지 안내 플래그만 켠다(조회는 계속 가능·로그아웃시키지 않는다).
 * 그 외 403·4xx·5xx는 그대로 throw해 호출부가 화면별로 처리한다(.catch(()=>{}) 금지).
 */
export function useSellerApi() {
  const config = useRuntimeConfig()
  const sellerAuth = useSellerAuthStore()

  return $fetch.create({
    baseURL: config.public.apiBase || '/api',
    onRequest({ options }) {
      options.headers.set('Authorization', `Bearer ${sellerAuth.token}`)
    },
    async onResponseError({ response }) {
      if (response.status === 401) {
        sellerAuth.logout()
        await navigateTo(SELLER_LOGIN_PATH)
        return
      }
      if (response.status === 403 && extractProblemCode(response._data) === SELLER_SUSPENDED_ERROR_CODE) {
        sellerAuth.markSuspended()
      }
    },
  })
}

/** ProblemDetail 본문(RFC7807 + code 확장)에서 code를 꺼낸다. 비JSON·code 없음은 null. */
function extractProblemCode(body: unknown): string | null {
  const code = (body as { code?: unknown } | null)?.code
  return typeof code === 'string' ? code : null
}
