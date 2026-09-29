import type { CartItemView, CartResponse } from '~/types/cart'

/** 장바구니 상태(setup store). 헤더 뱃지 count 원천 + 장바구니 페이지 품목/조작(FE-10b P2)을 제공한다. */
export const useCartStore = defineStore('cart', () => {
  // 담김 품목 전량. count는 length 파생(뱃지·선택 무관 전체 개수).
  const items = ref<CartItemView[]>([])
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

    const response = await useBuyerApi()<CartResponse>('/v1/cart')
    items.value = response.items
  }

  /**
   * 장바구니 담기(POST /api/v1/cart/items). 성공 후 load()로 재조회해 뱃지·items를 서버 상태와 정합시킨다
   * (응답 body 대신 재load — 조작 계약과 일관·GET이 enrich 최신값 원천). 실패(401/403/404/409)는 throw해 호출부가 처리한다.
   */
  async function add(variantPublicId: string, quantity: number): Promise<void> {
    await useBuyerApi()('/v1/cart/items', {
      method: 'POST',
      body: { variantPublicId, quantity },
    })
    await load()
  }

  /**
   * 수량 변경(PATCH /api/v1/cart/items/quantity·절대값). 성공 후 load()로 재조회(응답 Void라 재load가 유일 정합).
   * 실패(401/403/404·@Min(1) 400)는 throw해 호출부가 처리한다.
   */
  async function updateQuantity(variantPublicId: string, quantity: number): Promise<void> {
    await useBuyerApi()('/v1/cart/items/quantity', {
      method: 'PATCH',
      body: { variantPublicId, quantity },
    })
    await load()
  }

  /** 단건 선택 토글(PATCH /api/v1/cart/items/selected). 성공 후 재load. 실패는 throw. */
  async function setSelected(variantPublicId: string, selected: boolean): Promise<void> {
    await useBuyerApi()('/v1/cart/items/selected', {
      method: 'PATCH',
      body: { variantPublicId, selected },
    })
    await load()
  }

  /** 전체 선택 토글(PATCH /api/v1/cart/items/selected/all·대상키 없음). 성공 후 재load. 실패는 throw. */
  async function setSelectedAll(selected: boolean): Promise<void> {
    await useBuyerApi()('/v1/cart/items/selected/all', {
      method: 'PATCH',
      body: { selected },
    })
    await load()
  }

  /** 삭제(DELETE /api/v1/cart/items·단건도 배열 1개). 성공 후 재load. 실패는 throw. */
  async function remove(variantPublicIds: string[]): Promise<void> {
    await useBuyerApi()('/v1/cart/items', {
      method: 'DELETE',
      body: { variantPublicIds },
    })
    await load()
  }

  /** 장바구니 비우기(로그아웃 시 STEP 3에서 호출). */
  function clear(): void {
    items.value = []
  }

  return { items, count, load, add, updateQuantity, setSelected, setSelectedAll, remove, clear }
})
