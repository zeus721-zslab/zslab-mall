import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import CheckoutPage from '~/pages/checkout/index.vue'
import type { CartItemView } from '~/types/cart'
import type { Address } from '~/types/address'

// D-268 SEC-02: 주문서 422 중 미결제 한도 초과만 전용 문구, 나머지 422는 기존 문구. 장바구니·배송지·제출은 mock한다.
const { cartStoreMock, submitMock } = vi.hoisted(() => ({
  cartStoreMock: { items: [] as CartItemView[], load: vi.fn(async () => {}) },
  submitMock: vi.fn(),
}))
mockNuxtImport('useCartStore', () => () => cartStoreMock)
// 기본 배송지 1건을 돌려 setup이 필수 4필드를 채우게 한다(장바구니 조회 호출은 data를 쓰지 않는다).
const { DEFAULT_ADDRESS } = vi.hoisted(() => ({
  DEFAULT_ADDRESS: {
    id: 1,
    isDefault: true,
    recipientName: '홍길동',
    recipientPhone: '010-1234-5678',
    zonecode: '06236',
    addressRoad: '서울 강남대로 1',
  } satisfies Address,
}))
mockNuxtImport('useAsyncData', () => () => ({
  data: ref([DEFAULT_ADDRESS]),
  pending: ref(false),
  error: ref(null),
  refresh: vi.fn(),
}))
mockNuxtImport('useAddresses', () => () => ({ listAddresses: vi.fn(async () => []), createAddress: vi.fn() }))
mockNuxtImport('useCheckout', () => () => ({ submit: submitMock, submitOrder: vi.fn() }))

const CART_ITEM: CartItemView = {
  variantPublicId: 'var_page',
  quantity: 1,
  selected: true,
  productName: '상품',
  sellerName: '샵',
  displayPrice: 10000,
  quantityAvailable: 10,
  purchasable: true,
  thumbnailUrl: null,
}

async function submitWithError(error: { statusCode: number; data: { code: string } }): Promise<string> {
  submitMock.mockRejectedValue(error)
  const wrapper = await mountSuspended(CheckoutPage)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  expect(submitMock).toHaveBeenCalledTimes(1)
  return wrapper.text()
}

describe('pages/checkout 422 분기(D-268 SEC-02)', () => {
  beforeEach(() => {
    cartStoreMock.items = [CART_ITEM]
    submitMock.mockReset()
  })

  it('422 UNPAID_ORDER_LIMIT_EXCEEDED → 미결제 한도 전용 문구', async () => {
    const text = await submitWithError({ statusCode: 422, data: { code: 'UNPAID_ORDER_LIMIT_EXCEEDED' } })
    expect(text).toContain(
      '결제 대기 중인 주문이 3건 있습니다. 주문 내역에서 기존 주문을 결제해 주세요. 결제하지 않은 주문은 30분 후 자동 취소됩니다.',
    )
    expect(text).not.toContain('재고 부족 또는 판매 중지')
  })

  it('422 UNPAID_VARIANT_QUANTITY_LIMIT_EXCEEDED → 옵션 수량 상한 전용 문구(SEC-02 잔여)', async () => {
    const text = await submitWithError({ statusCode: 422, data: { code: 'UNPAID_VARIANT_QUANTITY_LIMIT_EXCEEDED' } })
    expect(text).toContain('결제 대기 중인 주문을 합쳐 한 옵션을 주문할 수 있는 수량을 넘었습니다. 수량을 줄이거나 기존 주문을 결제해 주세요.')
    expect(text).not.toContain('재고 부족 또는 판매 중지')
  })

  it('그 밖의 422(ORDER_NOT_PAYABLE) → 기존 문구 유지', async () => {
    const text = await submitWithError({ statusCode: 422, data: { code: 'ORDER_NOT_PAYABLE' } })
    expect(text).toContain('선택하신 상품을 지금 주문할 수 없습니다(재고 부족 또는 판매 중지).')
    expect(text).not.toContain('결제 대기 중인 주문')
  })
})

// PF-16 · PF-21: 오류 알림의 링크 자리는 하나({to, label})를 공유한다 — 결제 준비 실패 = 주문 상세 · 미결제 한도 = 주문 내역 · 빈 선택 = 장바구니.
// PF-17: 오류가 뜨면 알림 위치로 스크롤한다(하단 바에서 제출해도 알림이 화면 밖에 남지 않게).
describe('pages/checkout 오류 링크·스크롤(PF-16 · PF-21 · PF-17)', () => {
  beforeEach(() => {
    cartStoreMock.items = [CART_ITEM]
    submitMock.mockReset()
  })

  async function mountAndSubmit(): Promise<VueWrapper> {
    const wrapper = await mountSuspended(CheckoutPage)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    return wrapper
  }

  it('결제 준비 실패(publicId null) → next.retryPaymentUrl의 주문번호로 주문 상세 링크', async () => {
    submitMock.mockResolvedValue({
      data: {
        payment: { publicId: null, status: { code: 'INITIATE_FAILED', label: '결제 준비 실패' }, redirectUrl: null, expiresAt: null },
        next: { retryPaymentUrl: '/api/v1/orders/ord_01PF16/payments' },
      },
      status: 200,
      location: null,
    })
    const wrapper = await mountAndSubmit()
    const link = wrapper.find('a[href="/orders/ord_01PF16"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('주문 상세에서 결제하기')
    expect(wrapper.find('a[href="/cart"]').exists()).toBe(false)
  })

  it('422 UNPAID_ORDER_LIMIT_EXCEEDED → 주문 내역 링크', async () => {
    submitMock.mockRejectedValue({ statusCode: 422, data: { code: 'UNPAID_ORDER_LIMIT_EXCEEDED' } })
    const wrapper = await mountAndSubmit()
    expect(wrapper.find('a[href="/orders"]').text()).toBe('주문 내역으로 이동')
  })

  it('422 CART_CHECKOUT_EMPTY → 장바구니 링크 유지', async () => {
    submitMock.mockRejectedValue({ statusCode: 422, data: { code: 'CART_CHECKOUT_EMPTY' } })
    const wrapper = await mountAndSubmit()
    expect(wrapper.find('a[href="/cart"]').text()).toBe('장바구니로 이동')
  })

  it('오류가 뜨면 알림 위치로 스크롤한다', async () => {
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')
    submitMock.mockRejectedValue({ statusCode: 422, data: { code: 'ORDER_NOT_PAYABLE' } })
    await mountAndSubmit()
    expect(scrollSpy).toHaveBeenCalled()
    scrollSpy.mockRestore()
  })
})
