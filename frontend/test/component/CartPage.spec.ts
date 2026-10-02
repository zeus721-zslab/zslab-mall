import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import CartPage from '~/pages/cart.vue'
import type { CartItemView } from '~/types/cart'

// 장바구니 옵션 라벨(Track 75·FE-21): store items만 mock해 조건부 렌더를 검증한다.
const { cartStoreMock } = vi.hoisted(() => ({
  cartStoreMock: { items: [] as CartItemView[], load: vi.fn(async () => {}), updateQuantity: vi.fn() },
}))
mockNuxtImport('useCartStore', () => () => cartStoreMock)
mockNuxtImport('useAsyncData', () => () => ({ pending: ref(false), error: ref(null), refresh: vi.fn() }))

function item(overrides: Partial<CartItemView> & { variantPublicId: string }): CartItemView {
  return {
    quantity: 1,
    selected: true,
    productName: '상품',
    sellerName: '샵',
    displayPrice: 10000,
    quantityAvailable: 10,
    purchasable: true,
    thumbnailUrl: null,
    ...overrides,
  }
}

describe('pages/cart.vue 옵션 라벨', () => {
  beforeEach(() => {
    cartStoreMock.items = []
  })

  it('optionLabel 있음 → 상품명 아래 렌더', async () => {
    cartStoreMock.items = [item({ variantPublicId: 'var_opt', optionLabel: '색상: 블랙 / 사이즈: M' })]
    const wrapper = await mountSuspended(CartPage)
    const label = wrapper.find('[data-testid="item-option-label"]')
    expect(label.exists()).toBe(true)
    expect(label.text()).toBe('색상: 블랙 / 사이즈: M')
  })

  it('optionLabel 없음 → 미렌더', async () => {
    cartStoreMock.items = [item({ variantPublicId: 'var_plain' })]
    const wrapper = await mountSuspended(CartPage)
    expect(wrapper.text()).toContain('상품')
    expect(wrapper.find('[data-testid="item-option-label"]').exists()).toBe(false)
  })
})

// P-08: 수량 상한 999(BE CartItem.MAX_QUANTITY). 상한에서 +는 요청 없이 안내, 서버 400 수량 오류도 같은 안내.
describe('pages/cart.vue 수량 상한(P-08)', () => {
  const LIMIT_NOTICE = '수량은 최대 999개까지 선택할 수 있습니다.'

  beforeEach(() => {
    cartStoreMock.items = []
    cartStoreMock.updateQuantity.mockReset()
  })

  it('999 품목 + → 수량 변경 요청 없음 · 상한 안내', async () => {
    cartStoreMock.items = [item({ variantPublicId: 'var_max', quantity: 999, quantityAvailable: 2000 })]
    const wrapper = await mountSuspended(CartPage)
    await wrapper.find('button[aria-label="수량 증가"]').trigger('click')
    await flushPromises()
    expect(cartStoreMock.updateQuantity).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(LIMIT_NOTICE)
  })

  it('998 품목 + → 999로 변경 요청(상한 이내)', async () => {
    cartStoreMock.updateQuantity.mockResolvedValue(undefined)
    cartStoreMock.items = [item({ variantPublicId: 'var_near', quantity: 998, quantityAvailable: 2000 })]
    const wrapper = await mountSuspended(CartPage)
    await wrapper.find('button[aria-label="수량 증가"]').trigger('click')
    await flushPromises()
    expect(cartStoreMock.updateQuantity).toHaveBeenCalledWith('var_near', 999)
    expect(wrapper.text()).not.toContain(LIMIT_NOTICE)
  })

  it('서버 400 VALIDATION_FAILED(quantity) → 상한 안내 문구', async () => {
    cartStoreMock.updateQuantity.mockRejectedValue({
      statusCode: 400,
      data: { code: 'VALIDATION_FAILED', fieldErrors: [{ field: 'quantity', message: '999 이하여야 합니다' }] },
    })
    cartStoreMock.items = [item({ variantPublicId: 'var_err', quantity: 5 })]
    const wrapper = await mountSuspended(CartPage)
    await wrapper.find('button[aria-label="수량 증가"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(LIMIT_NOTICE)
  })

  it('서버 422 CART_ITEM_QUANTITY_LIMIT_EXCEEDED(합산 초과) → 합산 상한 전용 문구', async () => {
    cartStoreMock.updateQuantity.mockRejectedValue({ statusCode: 422, data: { code: 'CART_ITEM_QUANTITY_LIMIT_EXCEEDED' } })
    cartStoreMock.items = [item({ variantPublicId: 'var_sum', quantity: 5 })]
    const wrapper = await mountSuspended(CartPage)
    await wrapper.find('button[aria-label="수량 증가"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('장바구니에 담긴 수량과 합쳐 최대 999개까지 담을 수 있습니다.')
  })
})
