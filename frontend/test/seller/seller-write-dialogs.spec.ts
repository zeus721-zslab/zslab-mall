import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { VSelect } from 'vuetify/components'
import { flushPromises } from '@vue/test-utils'
import SellerShipmentDialog from '#layers/seller/app/components/seller/SellerShipmentDialog.vue'
import SellerMarkDeliveredDialog from '#layers/seller/app/components/seller/SellerMarkDeliveredDialog.vue'
import SellerDeliveryTrackingDialog from '#layers/seller/app/components/seller/SellerDeliveryTrackingDialog.vue'

/**
 * 셀러 쓰기 다이얼로그 3종(발송·배송완료·송장 정정)의 에러 분기(Track 90-B-3·FE-44 §8 이월): **403 SELLER_SUSPENDED는 호출부가 danger 토스트로 직접 표시**
 * (배너에만 의존 금지) 후 cancel · 422/404 상태 경합은 warning + stale · 송장 형식은 제출 전 클라이언트 검증 + 서버 400 fieldErrors 문구를 필드 오류로
 * 유지(D-227) · 성공은 done. API·토스트는 mock(실 네트워크 없음).
 */
const { ordersApiMock, deliveriesApiMock, toastMock } = vi.hoisted(() => ({
  ordersApiMock: { prepareShipment: vi.fn() },
  deliveriesApiMock: { markDelivered: vi.fn(), correctTracking: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/seller/app/composables/useSellerOrders', () => ({ useSellerOrders: () => ordersApiMock }))
vi.mock('#layers/seller/app/composables/useSellerDeliveries', () => ({ useSellerDeliveries: () => deliveriesApiMock }))
vi.mock('#layers/seller/app/composables/useSellerToast', () => ({ useSellerToast: () => toastMock }))

const SUSPENDED_403 = { status: 403, data: { code: 'SELLER_SUSPENDED', detail: '정지' } }
const SUSPENDED_MESSAGE = '정지 상태의 셀러는 변경 작업을 할 수 없습니다. 조회만 가능하며 문의는 관리자에게 하세요.'
const ORDER_ITEM = { orderItemId: 'oit_1', orderNo: '20260917-A', productName: '반찬통', optionLabel: undefined, quantity: 1 }
const DELIVERY = { deliveryId: 'dlv_1', orderNo: '20260917-A', productName: '반찬통', carrier: 'CJ' as const, trackingNo: 'TRK-00001' }
const TRACKING_NO_FORMAT_MESSAGE = '송장번호는 숫자·영문·하이픈 8~20자로 입력해 주세요.'

function dialogBody() {
  // v-dialog는 teleport로 body에 그린다 → document에서 찾는다.
  return document.body
}

async function mountDialog(component: typeof SellerShipmentDialog | typeof SellerMarkDeliveredDialog | typeof SellerDeliveryTrackingDialog, props: Record<string, unknown>) {
  const wrapper = await mountSuspended(component, { props: { open: false, ...props }, global: { plugins: [createVuetify()] }, attachTo: document.body })
  await wrapper.setProps({ open: true })
  await flushPromises()
  return wrapper
}

async function clickOk(testId: string): Promise<void> {
  const button = dialogBody().querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)
  if (!button) throw new Error(`${testId} 없음`)
  button.click()
  await flushPromises()
}

describe('셀러 쓰기 다이얼로그 — 403 SELLER_SUSPENDED 표시·에러 분기', () => {
  beforeEach(() => {
    Object.values(ordersApiMock).forEach((fn) => fn.mockReset())
    Object.values(deliveriesApiMock).forEach((fn) => fn.mockReset())
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    document.body.innerHTML = ''
    // v-dialog(VOverlay)는 window.visualViewport를 읽는데 테스트 DOM에는 없다 → 최소 stub.
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('배송완료: 403 SELLER_SUSPENDED → danger 토스트(정지 문구) + cancel · 422 → warning + stale · 성공 → success + done', async () => {
    deliveriesApiMock.markDelivered.mockRejectedValueOnce(SUSPENDED_403)
    const wrapper = await mountDialog(SellerMarkDeliveredDialog, { item: DELIVERY })
    await clickOk('delivered-dialog-ok')
    expect(deliveriesApiMock.markDelivered).toHaveBeenCalledWith('dlv_1')
    expect(toastMock.danger).toHaveBeenCalledWith(SUSPENDED_MESSAGE)
    expect(wrapper.emitted('cancel')).toHaveLength(1)

    deliveriesApiMock.markDelivered.mockRejectedValueOnce({ status: 422, data: { code: 'DELIVERY_INVALID_STATE' } })
    await clickOk('delivered-dialog-ok')
    expect(toastMock.warning).toHaveBeenCalledWith(expect.stringContaining('배송완료·송장 정정은 배송중만'))
    expect(wrapper.emitted('stale')).toHaveLength(1)

    deliveriesApiMock.markDelivered.mockResolvedValueOnce({ deliveryPublicId: 'dlv_1', status: 'DELIVERED', carrier: 'CJ', trackingNo: 'TRK-00001' })
    await clickOk('delivered-dialog-ok')
    expect(toastMock.success).toHaveBeenCalledWith('배송완료로 처리했습니다.')
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('발송: 폼 검증(택배사·송장 필수·API 미호출) → 입력 후 403 SELLER_SUSPENDED → danger 토스트 + cancel · 422 ORDER_ITEM_INVALID_STATE → stale', async () => {
    const wrapper = await mountDialog(SellerShipmentDialog, { item: ORDER_ITEM })
    await clickOk('shipment-dialog-ok')
    expect(ordersApiMock.prepareShipment).not.toHaveBeenCalled()
    expect(dialogBody().textContent).toContain('택배사를 선택하세요.')
    expect(dialogBody().textContent).toContain('송장번호를 입력하세요.')

    wrapper.findComponent(VSelect).vm.$emit('update:modelValue', 'HANJIN')
    const input = dialogBody().querySelector<HTMLInputElement>('[data-testid="shipment-tracking-no"] input')
    if (!input) throw new Error('송장 입력 없음')
    input.value = ' TRK-NEW-01 '
    input.dispatchEvent(new Event('input'))
    await flushPromises()

    ordersApiMock.prepareShipment.mockRejectedValueOnce(SUSPENDED_403)
    await clickOk('shipment-dialog-ok')
    expect(ordersApiMock.prepareShipment).toHaveBeenCalledWith('oit_1', { carrier: 'HANJIN', trackingNo: 'TRK-NEW-01' })
    expect(toastMock.danger).toHaveBeenCalledWith(SUSPENDED_MESSAGE)
    expect(wrapper.emitted('cancel')).toHaveLength(1)

    ordersApiMock.prepareShipment.mockRejectedValueOnce({ status: 422, data: { code: 'ORDER_ITEM_INVALID_STATE' } })
    await clickOk('shipment-dialog-ok')
    expect(toastMock.warning).toHaveBeenCalledWith(expect.stringContaining('발송은 결제완료 품목만'))
    expect(wrapper.emitted('stale')).toHaveLength(1)
  })

  it('발송 송장 형식(D-227): 자모 입력 → 제출 전 클라이언트 검증 문구·API 미호출 / 서버 400 fieldErrors → 입력칸 아래 서버 문구(다이얼로그 유지·토스트 없음)', async () => {
    const wrapper = await mountDialog(SellerShipmentDialog, { item: ORDER_ITEM })
    wrapper.findComponent(VSelect).vm.$emit('update:modelValue', 'CJ')
    const input = dialogBody().querySelector<HTMLInputElement>('[data-testid="shipment-tracking-no"] input')
    if (!input) throw new Error('송장 입력 없음')
    input.value = 'ㅗㅗㅗ'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    await clickOk('shipment-dialog-ok')
    expect(ordersApiMock.prepareShipment).not.toHaveBeenCalled()
    expect(dialogBody().querySelector('[data-testid="shipment-tracking-no"]')?.textContent).toContain(TRACKING_NO_FORMAT_MESSAGE)

    input.value = 'CJ-00000001'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    const serverMessage = '서버가 보낸 송장 형식 문구'
    ordersApiMock.prepareShipment.mockRejectedValueOnce({
      status: 400, data: { code: 'VALIDATION_FAILED', fieldErrors: [{ field: 'trackingNo', message: serverMessage }] },
    })
    await clickOk('shipment-dialog-ok')
    expect(ordersApiMock.prepareShipment).toHaveBeenCalledWith('oit_1', { carrier: 'CJ', trackingNo: 'CJ-00000001' })
    expect(dialogBody().querySelector('[data-testid="shipment-tracking-no"]')?.textContent).toContain(serverMessage)
    expect(toastMock.danger).not.toHaveBeenCalled()
    expect(wrapper.emitted('cancel')).toBeUndefined()
  })

  it('송장 정정: 현재 값 프리필·사유 없으면 버튼 비활성 → 403 → danger + cancel · 400 형식 → 송장번호 필드 오류(다이얼로그 유지) · 성공 → info + done', async () => {
    const wrapper = await mountDialog(SellerDeliveryTrackingDialog, { item: DELIVERY })
    const trackingInput = dialogBody().querySelector<HTMLInputElement>('[data-testid="tracking-no"] input')
    expect(trackingInput?.value).toBe('TRK-00001')
    const okButton = dialogBody().querySelector<HTMLButtonElement>('[data-testid="tracking-dialog-ok"]')
    expect(okButton?.disabled).toBe(true)

    const reasonInput = dialogBody().querySelector<HTMLTextAreaElement>('[data-testid="tracking-reason"] textarea')
    if (!reasonInput) throw new Error('사유 입력 없음')
    reasonInput.value = '택배사 오선택'
    reasonInput.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(dialogBody().querySelector<HTMLButtonElement>('[data-testid="tracking-dialog-ok"]')?.disabled).toBe(false)

    deliveriesApiMock.correctTracking.mockRejectedValueOnce(SUSPENDED_403)
    await clickOk('tracking-dialog-ok')
    expect(deliveriesApiMock.correctTracking).toHaveBeenCalledWith('dlv_1', { carrier: 'CJ', trackingNo: 'TRK-00001', reason: '택배사 오선택' })
    expect(toastMock.danger).toHaveBeenCalledWith(SUSPENDED_MESSAGE)
    expect(wrapper.emitted('cancel')).toHaveLength(1)

    deliveriesApiMock.correctTracking.mockRejectedValueOnce({
      status: 400, data: { code: 'VALIDATION_FAILED', fieldErrors: [{ field: 'trackingNo', message: TRACKING_NO_FORMAT_MESSAGE }] },
    })
    await clickOk('tracking-dialog-ok')
    expect(dialogBody().textContent).toContain(TRACKING_NO_FORMAT_MESSAGE)
    expect(wrapper.emitted('stale')).toBeUndefined()

    deliveriesApiMock.correctTracking.mockResolvedValueOnce({ deliveryPublicId: 'dlv_1', status: 'SHIPPING', carrier: 'CJ', trackingNo: 'TRK-00001' })
    await clickOk('tracking-dialog-ok')
    expect(toastMock.info).toHaveBeenCalledWith('송장 정보를 수정했습니다.')
    expect(wrapper.emitted('done')).toHaveLength(1)
  })
})
