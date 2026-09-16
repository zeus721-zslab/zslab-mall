import { ADMIN_LOGIN_PATH, ADMIN_ROLE } from '#layers/admin/app/lib/constants/auth'
import { ensureVuetify } from '#layers/admin/app/lib/vuetify'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'

/**
 * Vuetify 관리자 한정 로딩 미들웨어(FE-22c). 보호 페이지는 definePageMeta({ middleware: ['admin', 'vuetify'] })로 admin 뒤에 붙이고,
 * /admin/login은 ['vuetify']만 붙인다(로그인 화면도 Vuetify 폼·미인증 상태에서 로드).
 * 순서와 무관하게 자체 판정도 둔다: 로그인 경로가 아니면서 관리자 세션(admin_token·FE-22d)이 아니면 로드하지 않는다 → 비관리자 경로에서 로드 0.
 * /admin/**는 CSR 전용(D-9)이지만 서버 실행 방어를 위해 client 한정.
 */
export default defineNuxtRouteMiddleware(async (to) => {
  if (import.meta.server) return
  const adminAuth = useAdminAuthStore()
  const isLoginPage = to.path === ADMIN_LOGIN_PATH
  const isAdminSession = adminAuth.isAuthenticated && adminAuth.role === ADMIN_ROLE
  if (!isLoginPage && !isAdminSession) return
  await ensureVuetify(useNuxtApp())
})
