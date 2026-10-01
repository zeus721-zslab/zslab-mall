import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminInboxSettlementPanel from '#layers/admin/app/components/admin/AdminInboxSettlementPanel.vue'

/**
 * 인박스 정산 패널(D-251) — 버튼은 정산 상세와 같은 판정(canConfirm·canPay·payBlockedReason) · 지급 성공은 processed(done) · 상태·계좌·음수 422는
 * 경고 후 패널만 다시 읽고 processed를 보내지 않는다(항목 유지). API·토스트는 mock.
 */
const { settlementsApiMock, toastMock } = vi.hoisted(() => ({
  settlementsApiMock: { get: vi.fn(), confirm: vi.fn(), pay: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/admin/app/composables/useAdminSettlements', () => ({ useAdminSettlements: () => settlementsApiMock }))
vi.mock('#layers/admin/app/composables/useAdminToast', () => ({ useAdminToast: () => toastMock }))

function settlement(overrides: Record<string, unknown> = {}) {
  return {
    id: 501, seller: { publicId: 'slr_1', companyName: '테스트상사' }, periodStart: '2026-08-01T00:00:00+09:00', periodEnd: '2026-08-31T23:59:59+09:00',
    grossAmount: 100000, feeAmount: 10000, refundAmount: 0, carryoverAmount: 0, netAmount: 90000, status: 'CONFIRMED',
    scheduledPayDate: '2026-10-10', bankAccountRegistered: true, saleItemCount: 3, refundItemCount: 0, carryoverItemCount: 0,
    bankAccount: { id: 1, bankCode: '004', accountHolder: '테스트상사', accountNumberSuffix: '1234', snapshot: false },
    ...overrides,
  }
}

async function mountPanel() {
  const wrapper = await mountSuspended(AdminInboxSettlementPanel, { props: { settlementRef: '501' }, global: { plugins: [createVuetify()] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function click(testId: string): Promise<void> {
  const element = document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  if (!element) throw new Error(`${testId} 없음`)
  element.click()
  await flushPromises()
}

describe('AdminInboxSettlementPanel — 정산 확정·지급(D-251)', () => {
  beforeEach(() => {
    Object.values(settlementsApiMock).forEach((fn) => fn.mockReset())
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('버튼 판정: 확정 대기 = 확정만 · 지급 대기 + 계좌 없음 = 지급 비활성 + 사유', async () => {
    settlementsApiMock.get.mockResolvedValueOnce(settlement({ status: 'PENDING' }))
    const pending = await mountPanel()
    expect(settlementsApiMock.get).toHaveBeenCalledWith(501)
    expect(document.body.querySelector('[data-testid="inbox-settlement-confirm"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="inbox-settlement-pay"]')).toBeNull()
    pending.unmount()
    document.body.innerHTML = ''

    settlementsApiMock.get.mockResolvedValueOnce(settlement({ bankAccountRegistered: false, bankAccount: undefined }))
    await mountPanel()
    expect(document.body.querySelector('[data-testid="inbox-settlement-confirm"]')).toBeNull()
    expect(document.body.querySelector<HTMLButtonElement>('[data-testid="inbox-settlement-pay"]')?.disabled).toBe(true)
    expect(document.body.querySelector('[data-testid="inbox-settlement-pay-blocked"]')).not.toBeNull()
  })

  it('지급 성공: 확인 문구(금액·계좌) → pay → processed(done)', async () => {
    settlementsApiMock.get.mockResolvedValueOnce(settlement())
    settlementsApiMock.pay.mockResolvedValueOnce({ settlementId: 501, status: 'PAID' })
    const wrapper = await mountPanel()
    await click('inbox-settlement-pay')
    expect(document.body.querySelector('[data-testid="inbox-settlement-pay-dialog"]')?.textContent).toContain('90,000원')
    await click('inbox-settlement-pay-dialog-ok')
    expect(settlementsApiMock.pay).toHaveBeenCalledWith(501)
    expect(wrapper.emitted('processed')).toEqual([['done']])
  })

  it.each(['SETTLEMENT_INVALID_STATE', 'SETTLEMENT_NET_NEGATIVE', 'SETTLEMENT_BANK_ACCOUNT_MISSING'])(
    '지급 422 %s: 경고 토스트 + 패널 재조회 · processed 없음',
    async (code) => {
      settlementsApiMock.get.mockResolvedValue(settlement())
      settlementsApiMock.pay.mockRejectedValueOnce({ status: 422, data: { code } })
      const wrapper = await mountPanel()
      await click('inbox-settlement-pay')
      await click('inbox-settlement-pay-dialog-ok')
      expect(toastMock.warning).toHaveBeenCalledTimes(1)
      expect(settlementsApiMock.get).toHaveBeenCalledTimes(2)
      expect(wrapper.emitted('processed')).toBeUndefined()
    },
  )

  it('그 외 오류(500): 위험 토스트만 · 재조회·processed 없음', async () => {
    settlementsApiMock.get.mockResolvedValue(settlement({ status: 'PENDING' }))
    settlementsApiMock.confirm.mockRejectedValueOnce({ status: 500, data: { code: 'INTERNAL_ERROR' } })
    const wrapper = await mountPanel()
    await click('inbox-settlement-confirm')
    await click('inbox-settlement-confirm-dialog-ok')
    expect(toastMock.danger).toHaveBeenCalledTimes(1)
    expect(settlementsApiMock.get).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('processed')).toBeUndefined()
  })
})
