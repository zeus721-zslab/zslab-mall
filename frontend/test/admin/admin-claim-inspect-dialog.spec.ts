import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminClaimInspectDialog from '#layers/admin/app/components/admin/AdminClaimInspectDialog.vue'

/**
 * 관리자 검수 다이얼로그 — 회수 확인 후 검수(Track 96-1 FE-53·C-10). pickupRequired 진입은 체크 전 제출 불가 → 체크 후 confirm-pickup → inspect
 * 순차 호출 · confirm-pickup 실패는 검수 미시작 · confirm-pickup 성공·inspect 실패는 "회수 확인은 반영됨" 구분 안내 + stale. API·토스트는 mock.
 */
const { ordersApiMock, toastMock } = vi.hoisted(() => ({
  ordersApiMock: { confirmPickupClaim: vi.fn(), inspectClaim: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/admin/app/composables/useAdminOrders', () => ({ useAdminOrders: () => ordersApiMock }))
vi.mock('#layers/admin/app/composables/useAdminToast', () => ({ useAdminToast: () => toastMock }))

const CLAIM_RESPONSE = { claimId: 'clm_1', status: 'APPROVED' }

function body() {
  return document.body
}

async function mountDialog(target: Record<string, unknown>) {
  const wrapper = await mountSuspended(AdminClaimInspectDialog, { props: { open: false, target: null }, global: { plugins: [createVuetify()] }, attachTo: document.body })
  await wrapper.setProps({ open: true, target })
  await flushPromises()
  return wrapper
}

async function click(testId: string): Promise<void> {
  const element = body().querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  if (!element) throw new Error(`${testId} 없음`)
  element.click()
  await flushPromises()
}

function okButton(): HTMLButtonElement {
  const button = body().querySelector<HTMLButtonElement>('[data-testid="inspect-dialog-ok"]')
  if (!button) throw new Error('ok 버튼 없음')
  return button
}

async function choosePassRestock(): Promise<void> {
  const pass = body().querySelector<HTMLInputElement>('[data-testid="inspect-result-PASS"] input')
  if (!pass) throw new Error('PASS 라디오 없음')
  pass.click()
  await flushPromises()
  const restock = body().querySelector<HTMLInputElement>('[data-testid="inspect-restock-true"] input')
  if (!restock) throw new Error('재입고 라디오 없음')
  restock.click()
  await flushPromises()
}

describe('AdminClaimInspectDialog — 회수 확인 후 검수(C-10)', () => {
  beforeEach(() => {
    Object.values(ordersApiMock).forEach((fn) => fn.mockReset())
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('회수 확인 후 진입(pickupRequired 없음): 체크박스 없음 · confirm-pickup 호출 없이 inspect만', async () => {
    ordersApiMock.inspectClaim.mockResolvedValueOnce(CLAIM_RESPONSE)
    const wrapper = await mountDialog({ claimId: 'clm_1', productName: '반찬통', claimType: 'RETURN' })
    expect(body().querySelector('[data-testid="inspect-pickup-check"]')).toBeNull()
    await choosePassRestock()
    await click('inspect-dialog-ok')
    expect(ordersApiMock.confirmPickupClaim).not.toHaveBeenCalled()
    expect(ordersApiMock.inspectClaim).toHaveBeenCalledWith('clm_1', { result: 'PASS', restock: true })
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('회수 확인 전 진입: 체크 전 제출 불가 → 체크 후 confirm-pickup → inspect 순서로 호출 → done', async () => {
    const wrapper = await mountDialog({ claimId: 'clm_1', productName: '반찬통', claimType: 'RETURN', pickupRequired: true })
    await choosePassRestock()
    expect(okButton().disabled).toBe(true)

    const check = body().querySelector<HTMLInputElement>('[data-testid="inspect-pickup-check"] input')
    if (!check) throw new Error('체크박스 없음')
    check.click()
    await flushPromises()
    expect(okButton().disabled).toBe(false)

    const order: string[] = []
    ordersApiMock.confirmPickupClaim.mockImplementationOnce(async () => { order.push('pickup'); return CLAIM_RESPONSE })
    ordersApiMock.inspectClaim.mockImplementationOnce(async () => { order.push('inspect'); return CLAIM_RESPONSE })
    await click('inspect-dialog-ok')
    expect(order).toEqual(['pickup', 'inspect'])
    expect(ordersApiMock.confirmPickupClaim).toHaveBeenCalledWith('clm_1')
    expect(toastMock.info).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('confirm-pickup 422 → warning + stale · inspect 미호출', async () => {
    ordersApiMock.confirmPickupClaim.mockRejectedValueOnce({ status: 422, data: { code: 'CLAIM_STATE_INVALID' } })
    const wrapper = await mountDialog({ claimId: 'clm_1', productName: '반찬통', claimType: 'RETURN', pickupRequired: true })
    await choosePassRestock()
    body().querySelector<HTMLInputElement>('[data-testid="inspect-pickup-check"] input')?.click()
    await flushPromises()
    await click('inspect-dialog-ok')
    expect(ordersApiMock.inspectClaim).not.toHaveBeenCalled()
    expect(toastMock.warning).toHaveBeenCalledTimes(1)
    expect(toastMock.warning.mock.calls[0]?.[0]).not.toContain('회수 확인은 반영')
    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('done')).toBeUndefined()
  })

  it('confirm-pickup 성공·inspect 실패 → "회수 확인은 반영" 구분 안내(warning) + stale · done 없음', async () => {
    ordersApiMock.confirmPickupClaim.mockResolvedValueOnce(CLAIM_RESPONSE)
    ordersApiMock.inspectClaim.mockRejectedValueOnce({ status: 500, data: { code: 'INTERNAL_ERROR', detail: '서버 오류' } })
    const wrapper = await mountDialog({ claimId: 'clm_1', productName: '반찬통', claimType: 'RETURN', pickupRequired: true })
    await choosePassRestock()
    body().querySelector<HTMLInputElement>('[data-testid="inspect-pickup-check"] input')?.click()
    await flushPromises()
    await click('inspect-dialog-ok')
    expect(ordersApiMock.confirmPickupClaim).toHaveBeenCalledTimes(1)
    expect(ordersApiMock.inspectClaim).toHaveBeenCalledTimes(1)
    expect(toastMock.warning).toHaveBeenCalledWith(expect.stringContaining('회수 확인은 반영되었습니다. 검수는 처리되지 않았습니다'))
    expect(toastMock.danger).not.toHaveBeenCalled()
    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('done')).toBeUndefined()
  })
})
