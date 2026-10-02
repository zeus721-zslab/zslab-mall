import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerInventoryTable from '#layers/seller/app/components/seller/SellerInventoryTable.vue'
import SellerOrderItemTable from '#layers/seller/app/components/seller/SellerOrderItemTable.vue'
import SellerShipmentDialog from '#layers/seller/app/components/seller/SellerShipmentDialog.vue'
import SellerInboxStockPanel from '#layers/seller/app/components/seller/SellerInboxStockPanel.vue'
import type { SellerOrderItemSummary } from '#layers/seller/app/types/seller-order'

/**
 * warn W10: 정지(SUSPENDED) 셀러는 쓰기 버튼이 비활성 + 툴팁("정지 상태에서는 변경할 수 없습니다"). 정지 상태는 배너와 같은 sellerAuth.suspended를
 * 읽는다. 대표 화면(재고 표 입고·출고 · 주문 표 발송 · 발송 다이얼로그 확인 · 인박스 재고 패널 입고)으로 정지/정상 두 상태를 대조한다.
 */
// 렌더 후 정지 전환(403 수신) 케이스를 위해 반응형 객체로 둔다(seller-layout.spec 관례).
const { sellerAuthMock, inventoryApiMock } = await vi.hoisted(async () => {
  const { reactive } = await import('vue')
  return { sellerAuthMock: reactive({ suspended: false }), inventoryApiMock: { list: vi.fn() } }
})
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))
vi.mock('#layers/seller/app/composables/useSellerInventory', () => ({ useSellerInventory: () => inventoryApiMock }))

const TOOLTIP = '정지 상태에서는 변경할 수 없습니다'

const INVENTORY = [
  { variantPublicId: 'var_1', productPublicId: 'prd_1', productName: '반찬통', optionLabel: '색상: 블랙', sellerSku: 'SKU-BLK', quantityOnHand: 10, quantityReserved: 2, quantityAvailable: 8, updatedAt: '2026-09-17T17:29:23+09:00' },
]
const ORDER_ITEMS: SellerOrderItemSummary[] = [
  { orderItemId: 'oit_1', orderNo: '20260909-B', orderedAt: '2026-09-09T09:00:00+09:00', paidAt: '2026-09-09T09:05:00+09:00', productName: '주전자', quantity: 1, unitPrice: 45000, totalPrice: 45000, itemStatus: 'PAID', claimCount: 0 },
]

function body() {
  return document.body
}

function button(testId: string): HTMLButtonElement {
  const element = body().querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)
  if (!element) throw new Error(`${testId} 없음`)
  return element
}

function setSuspended(value: boolean): void {
  sellerAuthMock.suspended = value
}

describe('SellerSuspendedGuard — 정지 셀러 쓰기 버튼 비활성 + 툴팁(warn W10)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    setSuspended(false)
    inventoryApiMock.list.mockReset()
    // 다이얼로그 안 툴팁의 위치 계산이 visualViewport를 읽는다(jsdom 부재 · admin-inbox-settlement-panel.spec 관례).
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('재고 표: 정상 → 입고·출고 활성 · 가드 없음 / 정지 → 둘 다 비활성 · 툴팁 문구', async () => {
    const props = { items: INVENTORY, totalCount: 1, page: 0, size: 20, loading: false, pendingIds: new Set<string>() }
    const active = await mountSuspended(SellerInventoryTable, { props, global: { plugins: [createVuetify()] }, attachTo: body() })
    expect(button('row-inbound').disabled).toBe(false)
    expect(button('row-outbound').disabled).toBe(false)
    expect(body().querySelector('[data-testid="seller-suspended-guard"]')).toBeNull()
    active.unmount()
    document.body.innerHTML = ''

    setSuspended(true)
    const suspended = await mountSuspended(SellerInventoryTable, { props, global: { plugins: [createVuetify()] }, attachTo: body() })
    expect(button('row-inbound').disabled).toBe(true)
    expect(button('row-outbound').disabled).toBe(true)
    expect(body().querySelectorAll('[data-testid="seller-suspended-guard"]')).toHaveLength(2)
    const tooltips = suspended.findAllComponents({ name: 'VTooltip' })
    expect(tooltips).toHaveLength(2)
    expect(tooltips.map((tooltip) => tooltip.props('text'))).toEqual([TOOLTIP, TOOLTIP])
  })

  it('주문 표 발송: 정지 → 비활성 · 화면에 머무는 동안 정지가 켜져도(403 수신) 바로 비활성', async () => {
    await mountSuspended(SellerOrderItemTable, {
      props: { items: ORDER_ITEMS, totalCount: 1, page: 0, size: 20, loading: false, pendingIds: new Set<string>() },
      global: { plugins: [createVuetify()] },
      attachTo: body(),
    })
    expect(button('row-prepare-shipment').disabled).toBe(false)
    setSuspended(true)
    await flushPromises()
    expect(button('row-prepare-shipment').disabled).toBe(true)
  })

  it('발송 다이얼로그 확인 · 인박스 재고 패널 입고: 정지 → 비활성', async () => {
    setSuspended(true)
    const dialog = await mountSuspended(SellerShipmentDialog, {
      props: { open: false, item: { orderItemId: 'oit_1', orderNo: '20260917-A', productName: '반찬통', quantity: 1 } },
      global: { plugins: [createVuetify()] },
      attachTo: body(),
    })
    await dialog.setProps({ open: true })
    await flushPromises()
    expect(button('shipment-dialog-ok').disabled).toBe(true)
    dialog.unmount()
    document.body.innerHTML = ''

    inventoryApiMock.list.mockResolvedValueOnce({ items: INVENTORY, page: 0, size: 1, totalCount: 1, hasNext: false })
    await mountSuspended(SellerInboxStockPanel, { props: { variantPublicId: 'var_1', productName: '반찬통' }, global: { plugins: [createVuetify()] }, attachTo: body() })
    await flushPromises()
    expect(button('inbox-stock-inbound').disabled).toBe(true)
  })
})
