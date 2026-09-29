import { SELLER_LOGIN_PATH, SELLER_SUSPENDED_ERROR_CODE } from '#layers/seller/app/lib/constants/auth'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'
import { applyCsrfHeader } from '~/lib/csrf'

/**
 * 셀러 API 호출 래퍼(Track 90-A·관리자 useAdminApi 동형). /seller/**는 CSR 전용(ssr:false)이라 브라우저 baseURL(동일 Origin 상대경로)만 쓴다.
 * 인증은 BE 발급 HttpOnly 셀러 쿠키가 /api/v1/seller/** 요청에 자동 전송되므로 Authorization을 싣지 않고, unsafe 요청은 CSRF 헤더를 싣는다(D-235 F3).
 * BE 응답은 D-190 규칙으로 분기한다:
 * - 401(쿠키 만료·무효·PENDING/TERMINATED 셀러·소속 없음) → 셀러 상태만 로그아웃으로 둔 뒤 셀러 로그인으로(구매자·관리자 상태 유지).
 * - 403 SELLER_SUSPENDED(정지 셀러의 쓰기) → 세션 유지·정지 안내 플래그만 켠다(조회는 계속 가능·로그아웃시키지 않는다).
 * 그 외 403·4xx·5xx는 그대로 throw해 호출부가 화면별로 처리한다(.catch(()=>{}) 금지).
 */
export function useSellerApi() {
  const config = useRuntimeConfig()
  const sellerAuth = useSellerAuthStore()

  return $fetch.create({
    baseURL: config.public.apiBase || '/api',
    async onRequest({ options }) {
      await applyCsrfHeader(options)
    },
    async onResponseError({ response }) {
      if (response.status === 401) {
        sellerAuth.clearSession()
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
