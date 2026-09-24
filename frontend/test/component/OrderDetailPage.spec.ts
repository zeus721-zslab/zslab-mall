import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
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

// Track 105-4g-3: 머리 = 주문번호(orderNo 칩) · 주문 일시 · 결제 수단 라벨. 없는 필드는 행 생략·내부 id 미노출.
describe('pages/orders/[orderPublicId].vue 머리 주문번호·주문 일시·결제 수단', () => {
  beforeEach(() => {
    useOrderDetailMock.mockReset()
  })

  it('orderNo·orderedAt·payment 있음 → 칩·일시·결제 수단 한글 라벨', async () => {
    const detail: OrderDetail = {
      ...orderWith([orderItem({})]),
      orderNo: '20260920-AB12CD',
      orderedAt: '2026-09-20T12:00:00+09:00',
      payment: { method: 'KAKAO', paidAt: '2026-09-20T12:05:00+09:00' },
    }
    useOrderDetailMock.mockReturnValue({ data: ref(detail), pending: ref(false), error: ref(null), refresh: vi.fn() })
    const wrapper = await mountSuspended(OrderDetailPage)
    expect(wrapper.find('[data-testid="order-detail-order-no"]').text()).toBe('20260920-AB12CD')
    expect(wrapper.find('[data-testid="order-detail-ordered-at"]').text()).toBe('2026.09.20 12:00')
    expect(wrapper.find('[data-testid="order-detail-payment-method"]').text()).toBe('카카오페이')
  })

  it('옛 응답(orderNo·orderedAt 없음)·미결제(payment 없음) → 행 생략 · 내부 id 미노출', async () => {
    useOrderDetailMock.mockReturnValue({ data: ref(orderWith([orderItem({})])), pending: ref(false), error: ref(null), refresh: vi.fn() })
    const wrapper = await mountSuspended(OrderDetailPage)
    expect(wrapper.find('[data-testid="order-detail-order-no"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="order-detail-ordered-at"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="order-detail-payment-method"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('ord_test')
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
    // Portal 잔존물이 it 간 누적되지 않도록 body를 비운다.
    document.body.innerHTML = ''
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

  // FE-80: 품목 상태 = 품목 행마다 RenewBadge(의미 tone) · 머리의 주문 상태 배지는 없다.
  it('품목 배지 tone: 배송중 neutral · 배송완료 success · 주문 상태 라벨 미표시', async () => {
    mountWith([
      orderItem({ orderItemId: 'oit_1', status: { code: 'SHIPPING', label: '배송중' } }),
      delivered(),
    ])
    const wrapper = await mountSuspended(OrderDetailPage)
    const badges = wrapper.findAll('[data-testid="item-status"]')
    expect(badges.map((badge) => [badge.text(), badge.attributes('data-tone')])).toEqual([
      ['배송중', 'neutral'],
      ['배송완료', 'success'],
    ])
    expect(wrapper.find('section[aria-label="주문 정보"]').text()).not.toContain('결제완료')
  })

  it('결제 대기 주문 → 30분 자동 취소 안내', async () => {
    mountWith([orderItem({ status: { code: 'PENDING', label: '대기' } })], 'PENDING_PAYMENT')
    const wrapper = await mountSuspended(OrderDetailPage)
    expect(wrapper.find('[data-testid="order-payment-expire-guide"]').text()).toBe('30분 내 결제되지 않으면 주문이 자동 취소됩니다.')
  })

  // renew 확인 패널은 DialogConfirm(reka Portal → document.body)이라 wrapper가 아닌 document 기준으로 조회한다(FE-75).
  function dialogPart(testId: string): HTMLElement | null {
    return document.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  }

  async function openConfirm(wrapper: VueWrapper): Promise<void> {
    await wrapper.find('[data-testid="item-confirm-purchase"]').trigger('click')
    await flushPromises()
  }

  it('버튼 → 확인 패널(반품·교환 불가 경고) → 확정 → API 1회·재조회·성공 안내 / 취소는 호출 없음', async () => {
    const { refresh } = mountWith([delivered()])
    confirmPurchaseMock.mockResolvedValue({ orderItemId: 'oit_2', status: 'CONFIRMED' })
    const wrapper = await mountSuspended(OrderDetailPage)
    expect(dialogPart('item-confirm-panel')).toBeNull()
    await openConfirm(wrapper)
    expect(dialogPart('item-confirm-warning')?.textContent?.trim()).toBe(ITEM_CONFIRM_WARNING)
    dialogPart('item-confirm-cancel')!.click()
    await flushPromises()
    expect(dialogPart('item-confirm-panel')).toBeNull()
    expect(confirmPurchaseMock).not.toHaveBeenCalled()

    await openConfirm(wrapper)
    dialogPart('item-confirm-submit')!.click()
    await flushPromises()
    expect(confirmPurchaseMock).toHaveBeenCalledTimes(1)
    expect(confirmPurchaseMock).toHaveBeenCalledWith('ord_test', 'oit_2')
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="item-confirm-notice"]').text()).toBe('구매확정이 완료되었습니다.')
    expect(dialogPart('item-confirm-panel')).toBeNull()
  })

  // FE-79: 설명 = 대상(상품명 · 옵션) · 결과 = 안내(규약 경고 · RenewNotice warning) · 확인 버튼 = 동작명 · tone primary.
  // FE-80: 설명은 두 줄("구매 확정할 품목" / 대상)이고 대상에 조사를 붙이지 않는다.
  it('확인창 = 대상 설명 · 결과 안내(warning) · "구매 확정하기"(btn-primary)', async () => {
    mountWith([orderItem({ orderItemId: 'oit_2', productName: '배송완료 상품', optionLabel: '색상: 블랙', status: { code: 'DELIVERED', label: '배송완료' } })])
    const wrapper = await mountSuspended(OrderDetailPage)
    await openConfirm(wrapper)
    const panel = dialogPart('item-confirm-panel')!
    expect(panel.querySelector('[data-slot="dialog-description"]')?.textContent).toContain('구매 확정할 품목')
    expect(dialogPart('item-confirm-target')?.textContent?.trim()).toBe('배송완료 상품 · 색상: 블랙')
    const notice = dialogPart('item-confirm-warning')!
    expect(notice.getAttribute('data-tone')).toBe('warning')
    expect(notice.textContent?.trim()).toBe(ITEM_CONFIRM_WARNING)
    const submit = dialogPart('item-confirm-submit')!
    expect(submit.textContent?.trim()).toBe('구매 확정하기')
    expect(submit.classList).toContain('btn-primary')
  })

  it('중복 제출 차단: 응답 전 두 번째 클릭은 호출 없음', async () => {
    mountWith([delivered()])
    let resolveCall: (value: { orderItemId: string; status: string }) => void = () => {}
    confirmPurchaseMock.mockReturnValue(new Promise((resolve) => { resolveCall = resolve }))
    const wrapper = await mountSuspended(OrderDetailPage)
    await openConfirm(wrapper)
    dialogPart('item-confirm-submit')!.click()
    dialogPart('item-confirm-submit')!.click()
    expect(confirmPurchaseMock).toHaveBeenCalledTimes(1)
    resolveCall({ orderItemId: 'oit_2', status: 'CONFIRMED' })
    await flushPromises()
  })

  it('422 실패 → 서버 detail 문구 안내 + 재조회(최신 상태 반영)', async () => {
    const { refresh } = mountWith([delivered()])
    confirmPurchaseMock.mockRejectedValue({ statusCode: 422, data: { detail: '구매확정할 수 없는 상태입니다' } })
    const wrapper = await mountSuspended(OrderDetailPage)
    await openConfirm(wrapper)
    dialogPart('item-confirm-submit')!.click()
    await flushPromises()
    expect(wrapper.find('[data-testid="item-confirm-notice"]').text()).toBe('구매확정할 수 없는 상태입니다')
    expect(refresh).toHaveBeenCalledTimes(1)
  })
})
