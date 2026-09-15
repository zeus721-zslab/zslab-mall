import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import CartPage from '~/pages/cart.vue'
import type { CartItemView } from '~/types/cart'

// 장바구니 옵션 라벨(Track 75·FE-21): store items만 mock해 조건부 렌더를 검증한다.
const { cartStoreMock } = vi.hoisted(() => ({
  cartStoreMock: { items: [] as CartItemView[], load: vi.fn(async () => {}) },
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
