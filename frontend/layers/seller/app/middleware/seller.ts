import { SELLER_LOGIN_PATH, SELLER_PASSWORD_CHANGE_PATH, SELLER_ROLE } from '#layers/seller/app/lib/constants/auth'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'

/**
 * 셀러 전용 라우트 가드(Track 90-A·D-235 F5). 셀러 로그인 상태(GET /api/v1/seller/me 확인)만 본다 — 구매자·관리자 로그인 유무와 무관.
 * - 셀러 세션 없음(401) → 셀러 로그인으로 유도하고 복귀 경로를 전달한다.
 * - role≠SELLER → 셀러 상태를 비우고 셀러 로그인.
 * - 임시 비밀번호 세션(passwordChangeRequired·D-3) → 비밀번호 변경 경로 외 이동은 전부 변경 경로로 돌린다(진입 차단).
 * 실인가는 서버(/api/v1/seller/** hasRole SELLER·D-190 상태 차단)가 SoT이며 이 가드는 진입 UX만 담당한다.
 * definePageMeta({ middleware: ['seller', 'seller-vuetify'] })로 부착한다.
 */
export default defineNuxtRouteMiddleware(async (to) => {
  const sellerAuth = useSellerAuthStore()
  await sellerAuth.ensureSession()
  if (!sellerAuth.isAuthenticated) {
    return navigateTo(`${SELLER_LOGIN_PATH}?redirect=${encodeURIComponent(to.fullPath)}`)
  }
  if (sellerAuth.role !== SELLER_ROLE) {
    sellerAuth.clearSession()
    return navigateTo(SELLER_LOGIN_PATH)
  }
  if (sellerAuth.passwordChangeRequired && to.path !== SELLER_PASSWORD_CHANGE_PATH) {
    return navigateTo(SELLER_PASSWORD_CHANGE_PATH)
  }
})
