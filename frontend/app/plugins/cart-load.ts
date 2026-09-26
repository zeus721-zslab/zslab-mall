import { BUYER_ROLE } from '~/lib/constants/auth'

/**
 * 앱 진입 시 장바구니를 SSR 1회 로드한다(뱃지 첫 페인트부터 정확·클라이언트 중복 fetch 없음).
 * callOnce: SSR에서 1회 실행 → Pinia state가 클라이언트로 하이드레이션. .client 미사용(SSR 로드가 목적).
 * 회복력: 뱃지는 비필수라 로드 실패가 페이지 렌더를 깨지 않도록 이 트리거만 degrade(items는 store 기본 []).
 * store.load()는 STEP 2 그대로(명시 호출부가 throw 처리) 두고, 수동 트리거 계층에서만 흡수한다.
 * 앱 시작 뒤의 인증 전환(FE-86)은 클라이언트 watch가 맡는다: BUYER 로그인 → load, 해제 → clear.
 */
export default defineNuxtPlugin(async () => {
  const auth = useAuthStore()
  const cart = useCartStore()
  // FE-86: SSR 1회 로드는 클라이언트 로그인(폼·데모·가입) 뒤 다시 돌지 않고, 다른 탭 로그아웃·쿠키 만료는 명시 clear를 거치지 않는다.
  // 비immediate라 하이드레이션 직후엔 요청하지 않는다(초기값은 SSR 로드분). ADMIN 가드보다 먼저 등록해 역할 전환도 따른다.
  if (import.meta.client) {
    watch(
      () => auth.isAuthenticated && auth.role === BUYER_ROLE,
      async (isBuyerSignedIn) => {
        if (!isBuyerSignedIn) {
          cart.clear()
          return
        }
        try {
          await cart.load()
        } catch (error) {
          console.error('[cart-load] 로그인 후 장바구니 로드 실패(뱃지 degrade):', error)
        }
      },
    )
  }
  // FE-22 D-2: ADMIN 토큰은 BUYER 전용 GET /cart가 403이라 호출 자체를 건너뛴다(store.load는 isAuthenticated만 보므로 여기서 가드).
  if (auth.isAuthenticated && auth.role !== BUYER_ROLE) {
    return
  }
  await callOnce(async () => {
    try {
      await cart.load()
    } catch (error) {
      console.error('[cart-load] 초기 장바구니 로드 실패(뱃지 degrade·렌더 계속):', error)
    }
  })
})
