import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerInventoryTable from '#layers/seller/app/components/seller/SellerInventoryTable.vue'
import SellerProductTable from '#layers/seller/app/components/seller/SellerProductTable.vue'
import SellerInventoryAdjustDialog from '#layers/seller/app/components/seller/SellerInventoryAdjustDialog.vue'

/**
 * 셀러 상품·재고 컴포넌트(Track 90-C-3): 표 2종(상태 칩·가용 0 danger 칩·수정 비활성·페이지 0-base 변환)과 입출고 다이얼로그의 에러 분기
 * (**403 SELLER_SUSPENDED는 호출부가 danger 토스트로 직접 표시** 후 cancel · 422 INVENTORY_INVARIANT_VIOLATION → warning + stale · 400 → 필드 오류 · 성공 → success + done).
 * API·토스트는 mock(실 네트워크 없음).
 */
const { inventoryApiMock, toastMock } = vi.hoisted(() => ({
  inventoryApiMock: { list: vi.fn(), markInbound: vi.fn(), markOutbound: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/seller/app/composables/useSellerInventory', () => ({ useSellerInventory: () => inventoryApiMock }))
vi.mock('#layers/seller/app/composables/useSellerToast', () => ({ useSellerToast: () => toastMock }))

const SUSPENDED_403 = { status: 403, data: { code: 'SELLER_SUSPENDED', detail: '정지' } }
const SUSPENDED_MESSAGE = '정지 상태의 셀러는 변경 작업을 할 수 없습니다. 조회만 가능하며 문의는 관리자에게 하세요.'

const PRODUCTS = [
  { productPublicId: 'prd_1', name: '반찬통', categoryId: 1, categoryName: '주방', status: 'SALE' as const, basePrice: 32000, thumbnailUrl: '/api/v1/files/products/a.jpg', variantCount: 2, createdAt: '2026-09-17T17:29:23+09:00', updatedAt: '2026-09-17T17:29:23+09:00' },
  { productPublicId: 'prd_2', name: '주전자', categoryId: 1, status: 'PENDING' as const, basePrice: 45000, variantCount: 1, createdAt: '2026-09-16T09:00:00+09:00', updatedAt: '2026-09-16T09:00:00+09:00' },
]

const INVENTORY = [
  { variantPublicId: 'var_1', productPublicId: 'prd_1', productName: '반찬통', optionLabel: '색상: 블랙', sellerSku: 'SKU-BLK', quantityOnHand: 10, quantityReserved: 2, quantityAvailable: 8, updatedAt: '2026-09-17T17:29:23+09:00' },
  { variantPublicId: 'var_2', productPublicId: 'prd_1', productName: '반찬통', optionLabel: '색상: 화이트', sellerSku: 'SKU-WHT', quantityOnHand: 0, quantityReserved: 0, quantityAvailable: 0 },
]

function body() {
  return document.body
}

async function clickOk(testId: string): Promise<void> {
  const button = body().querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)
  if (!button) throw new Error(`${testId} 없음`)
  button.click()
  await flushPromises()
}

function type(testId: string, value: string): void {
  const input = body().querySelector<HTMLInputElement>(`[data-testid="${testId}"] input`)
  if (!input) throw new Error(`${testId} 입력 없음`)
  input.value = value
  input.dispatchEvent(new Event('input'))
}

describe('SellerProductTable · SellerInventoryTable', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('상품 표: 상태 칩 라벨·의미 색·기본가·등록일(KST 오프셋 → 분 단위)·수정 버튼 → edit 이벤트 · 페이지 이벤트 0-base', async () => {
    const wrapper = await mountSuspended(SellerProductTable, {
      props: { items: PRODUCTS, totalCount: 2, page: 0, size: 20, loading: false },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    const chips = body().querySelectorAll('[data-testid="status-chip"]')
    expect(chips).toHaveLength(2)
    expect(chips[0]?.textContent?.trim()).toBe('판매중')
    expect(chips[0]?.className).toContain('slr-chip--success')
    expect(chips[1]?.textContent?.trim()).toBe('승인대기')
    expect(chips[1]?.className).toContain('slr-chip--warning')
    expect(body().querySelector('[data-testid="row-base-price"]')?.textContent).toBe('32,000원')
    expect(body().querySelector('[data-testid="row-created-at"]')?.textContent).toBe('2026.09.17 17:29')
    const editButtons = body().querySelectorAll<HTMLButtonElement>('[data-testid="row-edit"]')
    expect(editButtons).toHaveLength(2)
    expect(editButtons[0]?.disabled).toBe(false)
    editButtons[1]?.click()
    expect(wrapper.emitted('edit')?.[0]).toEqual([PRODUCTS[1]])
    // 썸네일 없는 행은 placeholder
    expect(body().querySelectorAll('.slr-thumb--placeholder')).toHaveLength(1)

    wrapper.vm.$emit('update:page', 1)
    expect(wrapper.emitted('update:page')?.[0]).toEqual([1])
  })

  it('재고 표: 가용 0 이하 행만 danger 칩 · 옵션 없음 대시 · 입고/출고 버튼 행마다 · pendingIds 행은 비활성', async () => {
    await mountSuspended(SellerInventoryTable, {
      props: { items: INVENTORY, totalCount: 2, page: 0, size: 20, loading: false, pendingIds: new Set(['var_2']) },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    expect(body().querySelectorAll('[data-testid="row-available-chip"]')).toHaveLength(1)
    expect(body().querySelector('[data-testid="row-available-chip"]')?.className).toContain('slr-chip--danger')
    expect(body().querySelectorAll('[data-testid="row-available"]')).toHaveLength(1)
    expect(body().querySelector('[data-testid="row-available"]')?.textContent).toBe('8')
    const optionLabels = Array.from(body().querySelectorAll('[data-testid="row-option-label"]')).map((node) => node.textContent?.trim())
    expect(optionLabels).toEqual(['색상: 블랙', '색상: 화이트'])
    const inbound = body().querySelectorAll<HTMLButtonElement>('[data-testid="row-inbound"]')
    const outbound = body().querySelectorAll<HTMLButtonElement>('[data-testid="row-outbound"]')
    expect(inbound).toHaveLength(2)
    expect(outbound).toHaveLength(2)
    expect(inbound[0]?.disabled).toBe(false)
    expect(inbound[1]?.disabled).toBe(true)
    expect(outbound[1]?.disabled).toBe(true)
  })
})

describe('SellerInventoryAdjustDialog — 검증·403·422·400·성공', () => {
  beforeEach(() => {
    Object.values(inventoryApiMock).forEach((fn) => fn.mockReset())
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    document.body.innerHTML = ''
    // v-dialog(VOverlay)는 window.visualViewport를 읽는데 테스트 DOM에는 없다 → 최소 stub.
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  async function mountDialog(mode: 'INBOUND' | 'OUTBOUND') {
    const wrapper = await mountSuspended(SellerInventoryAdjustDialog, {
      props: { open: false, mode, item: INVENTORY[0] },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()
    return wrapper
  }

  it('입고: 폼 검증(수량·사유·API 미호출) → 입력 후 403 SELLER_SUSPENDED → danger 토스트 + cancel → 성공 → success + done(body는 숫자 수량·trim 사유)', async () => {
    const wrapper = await mountDialog('INBOUND')
    expect(body().querySelector('[data-testid="adjust-title"]')?.textContent).toContain('입고 처리')
    expect(body().querySelector('[data-testid="adjust-item"]')?.textContent).toContain('반찬통 (색상: 블랙)')
    await clickOk('adjust-dialog-ok')
    expect(inventoryApiMock.markInbound).not.toHaveBeenCalled()
    expect(body().textContent).toContain('수량은 1 이상의 정수여야 합니다.')
    expect(body().textContent).toContain('사유를 입력하세요.')

    type('adjust-quantity', '5')
    type('adjust-reason', ' 추가 입고 ')
    await flushPromises()
    inventoryApiMock.markInbound.mockRejectedValueOnce(SUSPENDED_403)
    await clickOk('adjust-dialog-ok')
    expect(inventoryApiMock.markInbound).toHaveBeenCalledWith('var_1', { quantity: 5, reason: '추가 입고' })
    expect(toastMock.danger).toHaveBeenCalledWith(SUSPENDED_MESSAGE)
    expect(wrapper.emitted('cancel')).toHaveLength(1)

    inventoryApiMock.markInbound.mockResolvedValueOnce({ variantPublicId: 'var_1', quantityOnHand: 15, quantityReserved: 2, quantityAvailable: 13 })
    await clickOk('adjust-dialog-ok')
    expect(toastMock.success).toHaveBeenCalledWith('입고 5개 처리했습니다. 보유 15 · 가용 13')
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('출고: 422 INVENTORY_INVARIANT_VIOLATION → warning + stale · 400 VALIDATION_FAILED fieldErrors → 필드 오류(다이얼로그 유지)', async () => {
    const wrapper = await mountDialog('OUTBOUND')
    expect(body().querySelector('[data-testid="adjust-title"]')?.textContent).toContain('출고 처리')
    type('adjust-quantity', '99')
    type('adjust-reason', '파손 폐기')
    await flushPromises()

    inventoryApiMock.markOutbound.mockRejectedValueOnce({ status: 422, data: { code: 'INVENTORY_INVARIANT_VIOLATION' } })
    await clickOk('adjust-dialog-ok')
    expect(inventoryApiMock.markOutbound).toHaveBeenCalledWith('var_1', { quantity: 99, reason: '파손 폐기' })
    expect(toastMock.warning).toHaveBeenCalledWith(expect.stringContaining('재고 수량이 맞지 않습니다'))
    expect(wrapper.emitted('stale')).toHaveLength(1)

    inventoryApiMock.markOutbound.mockRejectedValueOnce({ status: 400, data: { code: 'VALIDATION_FAILED', fieldErrors: [{ field: 'reason', message: '사유는 필수입니다.' }] } })
    await clickOk('adjust-dialog-ok')
    expect(body().textContent).toContain('사유는 필수입니다.')
    expect(wrapper.emitted('done')).toBeUndefined()
    expect(wrapper.emitted('stale')).toHaveLength(1)
  })
})
