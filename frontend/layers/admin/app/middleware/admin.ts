import { ADMIN_LOGIN_PATH, ADMIN_ROLE } from '#layers/admin/app/lib/constants/auth'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'

/**
 * 관리자 전용 라우트 가드(FE-22·FE-22d 세션 분리·D-235 F5). 관리자 로그인 상태(GET /api/v1/admin/me 확인)만 본다 — 구매자 로그인 유무와 무관.
 * - 관리자 세션 없음(401) → 관리자 로그인으로 유도하고 복귀 경로를 전달한다.
 * - role≠ADMIN → 관리자 상태를 비우고 관리자 로그인.
 * 실인가는 서버(/api/v1/admin/** hasRole ADMIN 401/403)가 SoT이며 이 가드는 진입 UX만 담당한다.
 * definePageMeta({ middleware: ['admin', 'vuetify'] })로 부착한다.
 */
export default defineNuxtRouteMiddleware(async (to) => {
  const adminAuth = useAdminAuthStore()
  await adminAuth.ensureSession()
  if (!adminAuth.isAuthenticated) {
    return navigateTo(`${ADMIN_LOGIN_PATH}?redirect=${encodeURIComponent(to.fullPath)}`)
  }
  if (adminAuth.role !== ADMIN_ROLE) {
    adminAuth.clearSession()
    return navigateTo(ADMIN_LOGIN_PATH)
  }
})
