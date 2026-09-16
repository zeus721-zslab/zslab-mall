import { ADMIN_LOGIN_PATH, ADMIN_ROLE } from '#layers/admin/app/lib/constants/auth'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'

/**
 * 관리자 전용 라우트 가드(FE-22·FE-22d 세션 분리). 관리자 세션(admin_token)만 본다 — 사용자 auth_token 유무와 무관.
 * - admin_token 없음·만료 → 관리자 로그인으로 유도하고 복귀 경로를 전달한다.
 * - admin_token의 role≠ADMIN(비정상 토큰) → admin_token 제거 후 관리자 로그인.
 * 실인가는 서버(/api/v1/admin/** hasRole ADMIN 401/403)가 SoT이며 이 가드는 진입 UX만 담당한다.
 * definePageMeta({ middleware: ['admin', 'vuetify'] })로 부착한다.
 */
export default defineNuxtRouteMiddleware((to) => {
  const adminAuth = useAdminAuthStore()
  if (!adminAuth.isAuthenticated) {
    return navigateTo(`${ADMIN_LOGIN_PATH}?redirect=${encodeURIComponent(to.fullPath)}`)
  }
  if (adminAuth.role !== ADMIN_ROLE) {
    adminAuth.logout()
    return navigateTo(ADMIN_LOGIN_PATH)
  }
})
