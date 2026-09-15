import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import OrderItemList from '~/components/checkout/OrderItemList.vue'
import type { CartItemView } from '~/types/cart'

// 체크아웃 주문 요약 옵션 라벨(Track 75·FE-21): 값이 있을 때만 상품명 아래 렌더한다.
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

describe('components/checkout/OrderItemList.vue 옵션 라벨', () => {
  it('optionLabel 있음 → data-testid="item-option-label"로 렌더', async () => {
    const wrapper = await mountSuspended(OrderItemList, {
      props: { items: [item({ variantPublicId: 'var_opt', optionLabel: '색상: 블랙 / 사이즈: M' })] },
    })
    const label = wrapper.find('[data-testid="item-option-label"]')
    expect(label.exists()).toBe(true)
    expect(label.text()).toBe('색상: 블랙 / 사이즈: M')
  })

  it('optionLabel 없음(생략·null) → 미렌더', async () => {
    const wrapper = await mountSuspended(OrderItemList, {
      props: { items: [item({ variantPublicId: 'var_plain' }), item({ variantPublicId: 'var_null', optionLabel: null })] },
    })
    expect(wrapper.find('[data-testid="item-option-label"]').exists()).toBe(false)
  })
})
