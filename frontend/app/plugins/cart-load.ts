/**
 * 앱 진입 시 장바구니를 SSR 1회 로드한다(뱃지 첫 페인트부터 정확·클라이언트 중복 fetch 없음).
 * callOnce: SSR에서 1회 실행 → Pinia state가 클라이언트로 하이드레이션. .client 미사용(SSR 로드가 목적).
 * 회복력: 뱃지는 비필수라 로드 실패가 페이지 렌더를 깨지 않도록 이 트리거만 degrade(items는 store 기본 []).
 * store.load()는 STEP 2 그대로(명시 호출부가 throw 처리) 두고, 수동 트리거 계층에서만 흡수한다.
 */
export default defineNuxtPlugin(async () => {
  const cart = useCartStore()
  await callOnce(async () => {
    try {
      await cart.load()
    } catch (error) {
      console.error('[cart-load] 초기 장바구니 로드 실패(뱃지 degrade·렌더 계속):', error)
    }
  })
})
