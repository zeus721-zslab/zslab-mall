import { SELLER_LOGIN_PATH, SELLER_ROLE } from '#layers/seller/app/lib/constants/auth'
import { ensureSellerVuetify } from '#layers/seller/app/lib/vuetify'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'

/**
 * Vuetify 셀러 한정 로딩 미들웨어(관리자 FE-22c 동형). 보호 페이지는 definePageMeta({ middleware: ['seller', 'seller-vuetify'] })로 seller 뒤에 붙이고,
 * /seller/login은 ['seller-vuetify']만 붙인다(로그인 화면도 Vuetify 폼·미인증 상태에서 로드).
 * 파일명은 관리자 named 미들웨어 `vuetify`와 충돌하지 않도록 `seller-vuetify`(레이어 파일명 = 미들웨어명).
 * 순서와 무관하게 자체 판정도 둔다: 로그인 경로가 아니면서 셀러 세션(seller_token)이 아니면 로드하지 않는다 → 비셀러 경로에서 로드 0.
 * /seller/**는 CSR 전용이지만 서버 실행 방어를 위해 client 한정.
 */
export default defineNuxtRouteMiddleware(async (to) => {
  if (import.meta.server) return
  const sellerAuth = useSellerAuthStore()
  const isLoginPage = to.path === SELLER_LOGIN_PATH
  const isSellerSession = sellerAuth.isAuthenticated && sellerAuth.role === SELLER_ROLE
  if (!isLoginPage && !isSellerSession) return
  await ensureSellerVuetify(useNuxtApp())
})
