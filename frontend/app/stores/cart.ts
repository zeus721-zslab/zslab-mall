import type { CartResponse } from '~/types/cart'

/** 장바구니 상태(setup store). 이번 단계는 헤더 뱃지 카운트 원천만 제공한다. */
export const useCartStore = defineStore('cart', () => {
  // 요소 shape는 뱃지 카운트에 불필요(장바구니 페이지 트랙으로 이연). count는 length 파생.
  const items = ref<unknown[]>([])
  const count = computed<number>(() => items.value.length)

  /**
   * 장바구니 조회(GET /api/v1/cart). 인증 상태에서만 호출한다.
   * 미인증이면 요청하지 않고 items를 비운다(BUYER 전용 엔드포인트·401/403 회피).
   */
  async function load(): Promise<void> {
    const auth = useAuthStore()
    if (!auth.isAuthenticated) {
      items.value = []
      return
    }

    const config = useRuntimeConfig()
    // API base 이원화(useProducts와 동일): SSR은 내부 직결, 브라우저는 동일 Origin 상대경로.
    const baseUrl = import.meta.server
      ? `${config.apiInternalBase}/api`
      : config.public.apiBase || '/api'

    const response = await $fetch<CartResponse>('/v1/cart', {
      baseURL: baseUrl,
      headers: { Authorization: `Bearer ${auth.token}` },
    })
    items.value = response.items
  }

  /** 장바구니 비우기(로그아웃 시 STEP 3에서 호출). */
  function clear(): void {
    items.value = []
  }

  return { items, count, load, clear }
})
