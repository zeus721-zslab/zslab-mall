import { BUYER_ROLE } from '~/lib/constants/auth'

/**
 * BUYER 전용 라우트 가드(recon-report-76 §3-2). 미인증 또는 비-BUYER면 로그인으로 유도하고 복귀 경로를 전달한다.
 * useCookie 기반 auth store라 SSR/CSR 양쪽에서 안전하게 판정한다. 실인가는 서버(BUYER 전용 API 401/403)가 SoT이며,
 * 이 가드는 진입 UX(로그인 유도)만 담당한다. definePageMeta({ middleware: 'buyer' })로 부착한다.
 */
export default defineNuxtRouteMiddleware((to) => {
  const auth = useAuthStore()
  if (!auth.isAuthenticated || auth.role !== BUYER_ROLE) {
    return navigateTo(`/login?redirect=${encodeURIComponent(to.fullPath)}`)
  }
})
