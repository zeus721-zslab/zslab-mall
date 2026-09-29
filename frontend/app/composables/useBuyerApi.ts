import { applyCsrfHeader } from '~/lib/csrf'

/**
 * 구매자 API 호출 래퍼(D-235 PR2). 인증은 BE 발급 HttpOnly 구매자 쿠키(__Secure-buyer_at)가 자동 전송되므로 Authorization을 싣지 않는다.
 * API base 이원화: SSR은 내부 직결(apiInternalBase), 브라우저는 동일 Origin 상대경로. unsafe 요청은 CSRF 헤더를 싣는다(F3).
 * SSR은 서버→BE 직결이라 브라우저 쿠키가 자동으로 실리지 않으므로, 브라우저 요청의 Cookie 헤더를 이 래퍼의 BE 내부 base 요청에만 명시 전달한다(F8).
 * 401(쿠키 만료·무효)은 구매자 상태만 로그아웃으로 두고, 로그인 화면 이동은 기존대로 호출 페이지가 맡는다(F5).
 * 실패(RFC7807)는 그대로 throw해 호출부가 처리한다.
 */
export function useBuyerApi() {
  const config = useRuntimeConfig()
  const auth = useAuthStore()
  const requestCookie = import.meta.server ? useRequestHeaders(['cookie']).cookie : undefined

  return $fetch.create({
    baseURL: import.meta.server ? `${config.apiInternalBase}/api` : config.public.apiBase || '/api',
    async onRequest({ options }) {
      if (requestCookie) options.headers.set('cookie', requestCookie)
      await applyCsrfHeader(options)
    },
    onResponseError({ response }) {
      if (response.status === 401) auth.clearSession()
    },
  })
}
