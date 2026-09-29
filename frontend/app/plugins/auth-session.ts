/**
 * 구매자 로그인 상태 복원(D-235 F5). 앱 진입 시 GET /api/v1/users/me로 1회 확인한다 — SSR에서 확인한 상태가 클라이언트로 하이드레이션되어
 * 클라이언트는 다시 묻지 않는다. 장바구니 로드(cart-load)·라우트 미들웨어가 이 상태를 읽으므로 그보다 먼저 실행되게 한다(플러그인 파일명 순서).
 */
export default defineNuxtPlugin(async () => {
  await useAuthStore().ensureSession()
})
