import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminInboxDeliveryPanel from '#layers/admin/app/components/admin/AdminInboxDeliveryPanel.vue'

/**
 * 인박스 장기 배송중 패널 수령인(warn W12): 배송 상세 응답엔 최상위 recipientName이 없고 배송지 스냅샷(shippingAddress.recipientName)에만 있다.
 * API·토스트는 mock.
 */
const { deliveriesApiMock, ordersApiMock, toastMock } = vi.hoisted(() => ({
  deliveriesApiMock: { detail: vi.fn() },
  ordersApiMock: { markDelivered: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/admin/app/composables/useAdminDeliveries', () => ({ useAdminDeliveries: () => deliveriesApiMock }))
vi.mock('#layers/admin/app/composables/useAdminOrders', () => ({ useAdminOrders: () => ordersApiMock }))
vi.mock('#layers/admin/app/composables/useAdminToast', () => ({ useAdminToast: () => toastMock }))

// BE AdminDeliveryDetailResponse 모양(최상위 recipientName 없음).
const DETAIL = {
  deliveryId: 'dlv_1', orderNo: '20260920-A', productName: '반찬통', direction: 'OUTBOUND', status: 'SHIPPING', carrier: 'CJ', trackingNo: 'TRK-1',
  shippedAt: '2026-09-20T10:00:00+09:00', optionLabel: '색상: 블랙', quantity: 2,
  shippingAddress: { recipientName: '홍길동', recipientPhone: '010-1234-5678', zonecode: '06236', addressRoad: '서울 강남구 테헤란로 1' },
}

describe('AdminInboxDeliveryPanel — 수령인 표시(warn W12)', () => {
  beforeEach(() => {
    deliveriesApiMock.detail.mockReset()
    document.body.innerHTML = ''
  })

  it('수령인은 배송지 스냅샷의 이름 · 배송지가 없으면 "-"', async () => {
    deliveriesApiMock.detail.mockResolvedValueOnce(DETAIL)
    const withAddress = await mountSuspended(AdminInboxDeliveryPanel, { props: { deliveryPublicId: 'dlv_1' }, global: { plugins: [createVuetify()] }, attachTo: document.body })
    await flushPromises()
    expect(withAddress.text()).toContain('색상: 블랙 · 2개 · 홍길동')
    withAddress.unmount()

    deliveriesApiMock.detail.mockResolvedValueOnce({ ...DETAIL, shippingAddress: undefined })
    const withoutAddress = await mountSuspended(AdminInboxDeliveryPanel, { props: { deliveryPublicId: 'dlv_1' }, global: { plugins: [createVuetify()] }, attachTo: document.body })
    await flushPromises()
    expect(withoutAddress.text()).toContain('색상: 블랙 · 2개 · -')
  })
})
