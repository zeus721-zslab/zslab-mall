import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import OrderDetailPage from '~/pages/orders/[orderPublicId].vue'
import type { OrderDetail, OrderItem } from '~/types/order'
import { ITEM_CONFIRM_WARNING } from '~/lib/constants/order'

// 주문 상세 옵션 라벨(Track 75·FE-21): useOrderDetail만 mock해 스냅샷 값의 조건부 렌더를 검증한다.
const { useOrderDetailMock, routeMock, confirmPurchaseMock } = vi.hoisted(() => ({
  useOrderDetailMock: vi.fn(),
  routeMock: { query: {}, params: { orderPublicId: 'ord_test' }, meta: {} },
  confirmPurchaseMock: vi.fn(),
}))
mockNuxtImport('useOrderDetail', () => useOrderDetailMock)
mockNuxtImport('useOrderActions', () => () => ({ confirmPurchase: confirmPurchaseMock }))
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

  it('교환 완료 품목(exchangeCompleted) → 교환 요청 버튼 숨김·반품 요청 버튼 유지(FE-30-4)', async () => {
    useOrderDetailMock.mockReturnValue({
      data: ref(orderWith([
        orderItem({ orderItemId: 'oit_2', productName: '교환 완료 상품', status: { code: 'DELIVERED', label: '배송완료' }, exchangeCompleted: true }),
      ])),
      pending: ref(false),
      error: ref(null),
      refresh: vi.fn(),
    })
    const wrapper = await mountSuspended(OrderDetailPage)
    const buttons = wrapper.findAll('button').map((button) => button.text())
    expect(buttons.filter((text) => text === '반품 요청')).toHaveLength(1)
    expect(buttons.filter((text) => text === '교환 요청')).toHaveLength(0)
  })
})

// Track 96-1(FE-53): C-06 구매확정 버튼(DELIVERED만·확인 패널 경고·성공 재조회·실패 서버 detail·중복 제출 차단) + C-16 안내 문구.
describe('pages/orders/[orderPublicId].vue 구매확정(C-06)·안내 문구(C-16)', () => {
  const delivered = () => orderItem({ orderItemId: 'oit_2', productName: '배송완료 상품', status: { code: 'DELIVERED', label: '배송완료' } })
  function mountWith(items: OrderItem[], statusCode: string = 'PAID') {
    const refresh = vi.fn().mockResolvedValue(undefined)
    const detail = orderWith(items)
    detail.status = { code: statusCode, label: statusCode }
    useOrderDetailMock.mockReturnValue({ data: ref(detail), pending: ref(false), error: ref(null), refresh })
    return { refresh }
  }

  beforeEach(() => {
    useOrderDetailMock.mockReset()
    confirmPurchaseMock.mockReset()
  })

  it('DELIVERED 품목만 구매확정 버튼 + 자동 확정 안내(7일) · SHIPPING·PAID 품목엔 없음', async () => {
    mountWith([
      orderItem({ orderItemId: 'oit_1', status: { code: 'SHIPPING', label: '배송중' } }),
      delivered(),
    ])
    const wrapper = await mountSuspended(OrderDetailPage)
    expect(wrapper.findAll('[data-testid="item-confirm-purchase"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="item-auto-confirm-guide"]').text()).toBe('배송완료 7일 후 자동 구매확정됩니다.')
    expect(wrapper.find('[data-testid="order-payment-expire-guide"]').exists()).toBe(false)
  })

  it('결제 대기 주문 → 30분 자동 취소 안내', async () => {
    mountWith([orderItem({ status: { code: 'PENDING', label: '대기' } })], 'PENDING_PAYMENT')
    const wrapper = await mountSuspended(OrderDetailPage)
    expect(wrapper.find('[data-testid="order-payment-expire-guide"]').text()).toBe('30분 내 결제되지 않으면 주문이 자동 취소됩니다.')
  })

  it('버튼 → 확인 패널(반품·교환 불가 경고) → 확정 → API 1회·재조회·성공 안내 / 취소는 호출 없음', async () => {
    const { refresh } = mountWith([delivered()])
    confirmPurchaseMock.mockResolvedValue({ orderItemId: 'oit_2', status: 'CONFIRMED' })
    const wrapper = await mountSuspended(OrderDetailPage)
    expect(wrapper.find('[data-testid="item-confirm-panel"]').exists()).toBe(false)
    await wrapper.find('[data-testid="item-confirm-purchase"]').trigger('click')
    expect(wrapper.find('[data-testid="item-confirm-warning"]').text()).toBe(ITEM_CONFIRM_WARNING)
    await wrapper.find('[data-testid="item-confirm-cancel"]').trigger('click')
    expect(wrapper.find('[data-testid="item-confirm-panel"]').exists()).toBe(false)
    expect(confirmPurchaseMock).not.toHaveBeenCalled()

    await wrapper.find('[data-testid="item-confirm-purchase"]').trigger('click')
    await wrapper.find('[data-testid="item-confirm-submit"]').trigger('click')
    await flushPromises()
    expect(confirmPurchaseMock).toHaveBeenCalledTimes(1)
    expect(confirmPurchaseMock).toHaveBeenCalledWith('ord_test', 'oit_2')
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="item-confirm-notice"]').text()).toBe('구매확정이 완료되었습니다.')
    expect(wrapper.find('[data-testid="item-confirm-panel"]').exists()).toBe(false)
  })

  it('중복 제출 차단: 응답 전 두 번째 클릭은 호출 없음', async () => {
    mountWith([delivered()])
    let resolveCall: (value: { orderItemId: string; status: string }) => void = () => {}
    confirmPurchaseMock.mockReturnValue(new Promise((resolve) => { resolveCall = resolve }))
    const wrapper = await mountSuspended(OrderDetailPage)
    await wrapper.find('[data-testid="item-confirm-purchase"]').trigger('click')
    await wrapper.find('[data-testid="item-confirm-submit"]').trigger('click')
    await wrapper.find('[data-testid="item-confirm-submit"]').trigger('click')
    expect(confirmPurchaseMock).toHaveBeenCalledTimes(1)
    resolveCall({ orderItemId: 'oit_2', status: 'CONFIRMED' })
    await flushPromises()
  })

  it('422 실패 → 서버 detail 문구 안내 + 재조회(최신 상태 반영)', async () => {
    const { refresh } = mountWith([delivered()])
    confirmPurchaseMock.mockRejectedValue({ statusCode: 422, data: { detail: '구매확정할 수 없는 상태입니다' } })
    const wrapper = await mountSuspended(OrderDetailPage)
    await wrapper.find('[data-testid="item-confirm-purchase"]').trigger('click')
    await wrapper.find('[data-testid="item-confirm-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="item-confirm-notice"]').text()).toBe('구매확정할 수 없는 상태입니다')
    expect(refresh).toHaveBeenCalledTimes(1)
  })
})
