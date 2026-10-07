import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import CartPage from '~/pages/cart.vue'
import type { CartItemView } from '~/types/cart'

// 장바구니 옵션 라벨(Track 75·FE-21): store items만 mock해 조건부 렌더를 검증한다.
const { cartStoreMock } = vi.hoisted(() => ({
  cartStoreMock: { items: [] as CartItemView[], load: vi.fn(async () => {}), updateQuantity: vi.fn(), remove: vi.fn() },
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

// UX-06: 삭제는 확인창(DialogConfirm · reka Portal → document.body)에서 확인한 뒤에만 서버를 부른다.
describe('pages/cart.vue 삭제 확인(UX-06)', () => {
  function dialogPart(testId: string): HTMLElement | null {
    return document.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  }

  beforeEach(() => {
    cartStoreMock.remove.mockReset()
    cartStoreMock.remove.mockResolvedValue(undefined)
    cartStoreMock.items = [item({ variantPublicId: 'var_del', productName: '삭제 상품' })]
  })

  it('삭제 → 확인창만 열림(호출 없음) · 취소 → 호출 없음 · 확인 → DELETE 1회', async () => {
    const wrapper = await mountSuspended(CartPage, { attachTo: document.body })
    await wrapper.find('button[aria-label="삭제 상품 삭제"]').trigger('click')
    await flushPromises()
    expect(dialogPart('cart-remove-dialog')?.textContent).toContain('삭제 상품')
    expect(cartStoreMock.remove).not.toHaveBeenCalled()

    dialogPart('cart-remove-cancel')!.click()
    await flushPromises()
    expect(dialogPart('cart-remove-dialog')).toBeNull()
    expect(cartStoreMock.remove).not.toHaveBeenCalled()

    await wrapper.find('button[aria-label="삭제 상품 삭제"]').trigger('click')
    await flushPromises()
    dialogPart('cart-remove-confirm')!.click()
    await flushPromises()
    expect(cartStoreMock.remove).toHaveBeenCalledTimes(1)
    expect(cartStoreMock.remove).toHaveBeenCalledWith(['var_del'])
    expect(dialogPart('cart-remove-dialog')).toBeNull()
    wrapper.unmount()
  })

  // PF-17: 행 조작 실패 문구는 주문 금액 카드 안에 있어 375px에서는 목록 아래 — 실패하면 그 위치로 스크롤한다.
  it('조작 실패 → 오류 문구 위치로 스크롤', async () => {
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')
    cartStoreMock.updateQuantity.mockRejectedValue({ statusCode: 500 })
    const wrapper = await mountSuspended(CartPage)
    await wrapper.find('button[aria-label="수량 증가"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('요청을 처리하지 못했습니다.')
    expect(scrollSpy).toHaveBeenCalled()
    scrollSpy.mockRestore()
  })
})
