import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import OrderDetailPage from '~/pages/orders/[orderPublicId].vue'
import type { OrderDetail, OrderItem } from '~/types/order'

// 주문 상세 옵션 라벨(Track 75·FE-21): useOrderDetail만 mock해 스냅샷 값의 조건부 렌더를 검증한다.
const { useOrderDetailMock, routeMock } = vi.hoisted(() => ({
  useOrderDetailMock: vi.fn(),
  routeMock: { query: {}, params: { orderPublicId: 'ord_test' }, meta: {} },
}))
mockNuxtImport('useOrderDetail', () => useOrderDetailMock)
mockNuxtImport('useRoute', () => () => routeMock)

function orderWith(items: OrderItem[]): OrderDetail {
  return {
    orderId: 'ord_test',
    status: { code: 'PAID', label: '결제완료' },
    sellers: [{ sellerId: 'slr_1', companyName: '샵', items, subtotal: 10000 }],
    totalPrice: 10000,
    shippingAddress: null,
  }
}

function orderItem(overrides: Partial<OrderItem>): OrderItem {
  return {
    orderItemId: 'oit_1',
    productName: '상품',
    quantity: 1,
    unitPrice: 10000,
    totalPrice: 10000,
    status: { code: 'PAID', label: '결제완료' },
    ...overrides,
  }
}

describe('pages/orders/[orderPublicId].vue 옵션 라벨', () => {
  beforeEach(() => {
    useOrderDetailMock.mockReset()
  })

  it('optionLabel 있음 → 상품명 아래 렌더', async () => {
    useOrderDetailMock.mockReturnValue({
      data: ref(orderWith([orderItem({ optionLabel: '색상: 블랙 / 사이즈: M' })])),
      pending: ref(false),
      error: ref(null),
      refresh: vi.fn(),
    })
    const wrapper = await mountSuspended(OrderDetailPage)
    const label = wrapper.find('[data-testid="item-option-label"]')
    expect(label.exists()).toBe(true)
    expect(label.text()).toBe('색상: 블랙 / 사이즈: M')
  })

  it('optionLabel 없음(기존 주문·단순상품) → 미렌더', async () => {
    useOrderDetailMock.mockReturnValue({
      data: ref(orderWith([orderItem({})])),
      pending: ref(false),
      error: ref(null),
      refresh: vi.fn(),
    })
    const wrapper = await mountSuspended(OrderDetailPage)
    expect(wrapper.text()).toContain('상품')
    expect(wrapper.find('[data-testid="item-option-label"]').exists()).toBe(false)
  })
})

// FE-29: 반품 진입 버튼은 DELIVERED만(SHIPPING → RETURN 제거·D-170 배송완료 기준 기한).
describe('pages/orders/[orderPublicId].vue 클레임 진입 버튼(FE-29)', () => {
  beforeEach(() => {
    useOrderDetailMock.mockReset()
  })

  it('SHIPPING 품목 → 반품 요청 버튼 없음 / DELIVERED 품목 → 반품·교환 요청 버튼', async () => {
    useOrderDetailMock.mockReturnValue({
      data: ref(orderWith([
        orderItem({ orderItemId: 'oit_1', productName: '배송중 상품', status: { code: 'SHIPPING', label: '배송중' } }),
        orderItem({ orderItemId: 'oit_2', productName: '배송완료 상품', status: { code: 'DELIVERED', label: '배송완료' } }),
      ])),
      pending: ref(false),
      error: ref(null),
      refresh: vi.fn(),
    })
    const wrapper = await mountSuspended(OrderDetailPage)
    const buttons = wrapper.findAll('button').map((button) => button.text())
    expect(buttons.filter((text) => text === '반품 요청')).toHaveLength(1)
    expect(buttons.filter((text) => text === '교환 요청')).toHaveLength(1)
    expect(buttons.filter((text) => text === '취소 요청')).toHaveLength(0)
  })
})
