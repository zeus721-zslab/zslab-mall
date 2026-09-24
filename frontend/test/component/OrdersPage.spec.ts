import { describe, it, expect, beforeEach, afterEach, vi, type MockInstance } from 'vitest'
import { ref, type Ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#app'
import OrdersPage from '~/pages/orders/index.vue'
import type { OrderSummary, PagedResponse } from '~/types/order'

// FE-80·D-224 주문 목록 품목 상태 필터: URL(?itemStatus=)이 단일 소스라 조회 훅 인자·router.replace 쿼리만 검증한다(기준 스킨 renew).
// useRouter는 Nuxt 플러그인이 afterEach·beforeResolve를 걸어 통째로 mock할 수 없다 → 실제 라우터의 replace만 spy로 바꾼다.
const { useOrderListMock, useClaimListMock, routeMock } = vi.hoisted(() => ({
  useOrderListMock: vi.fn(),
  useClaimListMock: vi.fn(),
  routeMock: { query: {} as Record<string, string>, params: {}, meta: {}, path: '/orders' },
}))
mockNuxtImport('useOrderList', () => useOrderListMock)
mockNuxtImport('useClaimList', () => useClaimListMock)
mockNuxtImport('useOrderActions', () => () => ({ confirmPurchase: vi.fn() }))
mockNuxtImport('useRoute', () => () => routeMock)

let replaceMock: MockInstance
beforeEach(() => {
  replaceMock = vi.spyOn(useNuxtApp().$router, 'replace').mockResolvedValue(undefined)
})
afterEach(() => {
  replaceMock.mockRestore()
})

function deliveredOrder(): OrderSummary {
  return {
    orderId: 'ord_1',
    previewTitle: '양말',
    sellerCount: 1,
    totalPrice: 12000,
    status: { code: 'PAID', label: 'PAID' },
    orderedAt: '2026-09-20T10:00:00',
    activeClaims: [],
    items: [
      {
        orderItemId: 'oit_1',
        productName: '양말',
        quantity: 1,
        unitPrice: 12000,
        totalPrice: 12000,
        status: { code: 'DELIVERED', label: 'DELIVERED' },
        productId: 'prd_1',
        exchangeCompleted: false,
      },
    ],
  }
}

function page(items: OrderSummary[], hasNext: boolean = false): PagedResponse<OrderSummary> {
  return { items, page: 0, size: 10, totalCount: items.length, hasNext }
}

function mockLists(orders: PagedResponse<OrderSummary>): void {
  useOrderListMock.mockReturnValue({ data: ref(orders), pending: ref(false), error: ref(null), refresh: vi.fn() })
  useClaimListMock.mockReturnValue({ data: ref(undefined), pending: ref(false), status: ref('idle'), error: ref(null), refresh: vi.fn() })
}

/** useOrderList 두 번째 인자(품목 상태 필터 Ref)의 현재 값 — API 쿼리로 가는 값이다. */
function passedItemStatus(): string | null {
  const itemStatus = useOrderListMock.mock.calls[0]![1] as Ref<string | null>
  return itemStatus.value
}

function lastReplacedQuery(): Record<string, string> {
  const call = replaceMock.mock.calls.at(-1)![0] as { query: Record<string, string> }
  return call.query
}

describe('pages/orders/index.vue 품목 상태 필터(FE-80·D-224)', () => {
  beforeEach(() => {
    useOrderListMock.mockReset()
    useClaimListMock.mockReset()
    routeMock.query = {}
  })

  it('?itemStatus=DELIVERED → 조회에 전달 · 칩 "배송 완료 품목 · 최근 3개월"', async () => {
    routeMock.query = { itemStatus: 'DELIVERED' }
    mockLists(page([deliveredOrder()]))
    const wrapper = await mountSuspended(OrdersPage)
    expect(passedItemStatus()).toBe('DELIVERED')
    expect(wrapper.find('[data-testid="order-item-status-filter"]').text()).toContain('배송 완료 품목 · 최근 3개월')
  })

  it('허용 5값 밖(ORDERED·소문자)은 무시 → 필터 없이 조회 · 칩 없음', async () => {
    for (const raw of ['ORDERED', 'delivered']) {
      useOrderListMock.mockReset()
      routeMock.query = { itemStatus: raw }
      mockLists(page([deliveredOrder()]))
      const wrapper = await mountSuspended(OrdersPage)
      expect(passedItemStatus()).toBeNull()
      expect(wrapper.find('[data-testid="order-item-status-filter"]').exists()).toBe(false)
    }
  })

  it('칩 해제 → itemStatus 제거 + 첫 페이지', async () => {
    routeMock.query = { itemStatus: 'SHIPPING', page: '2' }
    mockLists(page([deliveredOrder()]))
    const wrapper = await mountSuspended(OrdersPage)
    await wrapper.find('[data-testid="order-item-status-filter-clear"]').trigger('click')
    expect(lastReplacedQuery()).toEqual({ page: '0' })
  })

  it('클레임 탭으로 바꾸면 itemStatus 제거 · 같은 탭 다음 페이지는 유지', async () => {
    routeMock.query = { itemStatus: 'DELIVERED' }
    mockLists(page([deliveredOrder()], true))
    const wrapper = await mountSuspended(OrdersPage)

    const next = wrapper.findAll('button').find((button) => button.text() === '다음')!
    await next.trigger('click')
    expect(lastReplacedQuery()).toEqual({ itemStatus: 'DELIVERED', tab: 'order', page: '1' })

    const claimTab = wrapper.findAll('[data-testid="order-tabs"] button').find((button) => button.text() === '취소·반품·교환')!
    await claimTab.trigger('click')
    expect(lastReplacedQuery()).toEqual({ tab: 'claim', page: '0' })
  })

  it('필터 결과가 비면 기간·단계 문구 + 필터 해제 보조 버튼(쇼핑하러 가기 대신)', async () => {
    routeMock.query = { itemStatus: 'CONFIRMED' }
    mockLists(page([]))
    const wrapper = await mountSuspended(OrdersPage)
    const empty = wrapper.find('[data-testid="order-list-empty"]')
    expect(empty.text()).toContain('최근 3개월 동안 구매 확정 품목이 있는 주문이 없어요')
    expect(empty.text()).not.toContain('쇼핑하러 가기')
    await wrapper.find('[data-testid="order-empty-filter-clear"]').trigger('click')
    expect(lastReplacedQuery()).toEqual({ page: '0' })
  })

  it('클레임 탭에서는 ?itemStatus가 남아 있어도 걸지 않는다', async () => {
    routeMock.query = { tab: 'claim', itemStatus: 'DELIVERED' }
    mockLists(page([]))
    const wrapper = await mountSuspended(OrdersPage)
    expect(passedItemStatus()).toBeNull()
    expect(wrapper.find('[data-testid="order-item-status-filter"]').exists()).toBe(false)
  })
})

describe('pages/orders/index.vue 주문 카드(FE-80)', () => {
  beforeEach(() => {
    useOrderListMock.mockReset()
    useClaimListMock.mockReset()
    routeMock.query = {}
  })

  it('머리 "상품 N개 · 총 금액" · 품목 배지 tone · 상품 상세 링크', async () => {
    mockLists(page([deliveredOrder()]))
    const wrapper = await mountSuspended(OrdersPage)
    expect(wrapper.find('[data-testid="order-card-summary"]').text().replace(/\s+/g, ' ')).toBe('상품 1개 · 총 12,000원')
    const badge = wrapper.find('[data-testid="order-item-status"]')
    expect([badge.text(), badge.attributes('data-tone')]).toEqual(['배송완료', 'success'])
    expect(wrapper.find('[data-testid="order-item-product-link"]').attributes('href')).toBe('/products/prd_1')
  })

  it('품목 요약이 없는 응답은 개수를 빼고 총액만', async () => {
    const order = deliveredOrder()
    delete order.items
    mockLists(page([order]))
    const wrapper = await mountSuspended(OrdersPage)
    expect(wrapper.find('[data-testid="order-card-summary"]').text().replace(/\s+/g, ' ')).toBe('총 12,000원')
  })
})
