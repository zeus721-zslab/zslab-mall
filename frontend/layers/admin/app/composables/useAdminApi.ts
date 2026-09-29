import { ADMIN_LOGIN_PATH } from '#layers/admin/app/lib/constants/auth'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'
import { applyCsrfHeader } from '~/lib/csrf'

/**
 * 관리자 API 호출 래퍼(FE-22·D-8·FE-22d·D-235 PR2). /admin/**는 CSR 전용(D-9 ssr:false)이라 브라우저 baseURL(동일 Origin 상대경로)만 쓴다.
 * 인증은 BE 발급 HttpOnly 관리자 쿠키가 /api/v1/admin/** 요청에 자동 전송되므로 Authorization을 싣지 않고, unsafe 요청은 CSRF 헤더를 싣는다(F3).
 * 401(쿠키 만료·무효)은 관리자 상태만 로그아웃으로 둔 뒤 관리자 로그인으로 보낸다(구매자·셀러 상태 유지).
 * 403·4xx·5xx는 그대로 throw해 호출부가 화면별로 처리한다(.catch(()=>{}) 금지).
 */
export function useAdminApi() {
  const config = useRuntimeConfig()
  const adminAuth = useAdminAuthStore()

  return $fetch.create({
    baseURL: config.public.apiBase || '/api',
    onRequest({ options }) {
      applyCsrfHeader(options)
    },
    async onResponseError({ response }) {
      if (response.status === 401) {
        adminAuth.clearSession()
        await navigateTo(ADMIN_LOGIN_PATH)
      }
    },
  })
}
