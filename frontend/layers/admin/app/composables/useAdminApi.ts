import { ADMIN_LOGIN_PATH } from '#layers/admin/app/lib/constants/auth'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'

/**
 * 관리자 API 호출 래퍼(FE-22·D-8·FE-22d). /admin/**는 CSR 전용(D-9 ssr:false)이라 브라우저 baseURL(동일 Origin 상대경로)만 쓴다.
 * 매 요청에 관리자 세션(admin_token) Bearer를 주입하고, 401(토큰 만료·무효)은 admin_token만 비운 뒤 관리자 로그인으로 보낸다(사용자 auth_token 유지).
 * 403·4xx·5xx는 그대로 throw해 호출부가 화면별로 처리한다(.catch(()=>{}) 금지).
 */
export function useAdminApi() {
  const config = useRuntimeConfig()
  const adminAuth = useAdminAuthStore()

  return $fetch.create({
    baseURL: config.public.apiBase || '/api',
    onRequest({ options }) {
      options.headers.set('Authorization', `Bearer ${adminAuth.token}`)
    },
    async onResponseError({ response }) {
      if (response.status === 401) {
        adminAuth.logout()
        await navigateTo(ADMIN_LOGIN_PATH)
      }
    },
  })
}
