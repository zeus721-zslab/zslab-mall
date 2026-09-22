import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerProductTable from '#layers/seller/app/components/seller/SellerProductTable.vue'
import SellerProductSaleStatusDialog from '#layers/seller/app/components/seller/SellerProductSaleStatusDialog.vue'
import SellerProductSaleStatusCard from '#layers/seller/app/components/seller/SellerProductSaleStatusCard.vue'
import {
  SELLER_ADMIN_STOPPED_NOTE,
  hasSellerSaleAction,
  resolveSellerSaleAction,
  saleActionConfirmMessage,
  toSaleStatusTarget,
} from '#layers/seller/app/lib/seller-product-sale-status'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'

/**
 * 셀러 판매중지·재판매·품절 셀프 전환(Track 96-5·D-206·FE-57): 액션 분기 순수 함수(상태·주체별) · 표 행 메뉴 노출·중지 주체 라벨 ·
 * 확인 다이얼로그 에러 분기(성공 → success + done · 422 PRODUCT_STOPPED_BY_ADMIN/PRODUCT_INVALID_STATE·404 → warning + stale ·
 * **403 SELLER_SUSPENDED는 호출부가 danger 토스트로 직접 표시** 후 cancel) · 판매 관리 카드(버튼 분기·품절 스위치 PATCH). API·토스트는 mock.
 */
const { productsApiMock, toastMock } = vi.hoisted(() => ({
  productsApiMock: { changeSaleStatus: vi.fn(), changeSoldOut: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/seller/app/composables/useSellerProducts', () => ({ useSellerProducts: () => productsApiMock }))
vi.mock('#layers/seller/app/composables/useSellerToast', () => ({ useSellerToast: () => toastMock }))

const SUSPENDED_403 = { status: 403, data: { code: 'SELLER_SUSPENDED', detail: '정지' } }
const ADMIN_STOPPED_422 = { status: 422, data: { code: 'PRODUCT_STOPPED_BY_ADMIN', detail: '관리자 중지' } }
const INVALID_STATE_422 = { status: 422, data: { code: 'PRODUCT_INVALID_STATE', detail: '전이 불가' } }

const BASE = { categoryId: 1, categoryName: '주방', basePrice: 10000, variantCount: 1, createdAt: '2026-09-22T09:00:00+09:00', updatedAt: '2026-09-22T09:00:00+09:00' }
const PRODUCTS = [
  { ...BASE, productPublicId: 'prd_sale', name: '판매중 상품', status: 'SALE' as const },
  { ...BASE, productPublicId: 'prd_seller', name: '셀러 중지 상품', status: 'STOPPED' as const, saleStopSource: 'SELLER' as const },
  { ...BASE, productPublicId: 'prd_admin', name: '관리자 중지 상품', status: 'STOPPED' as const, saleStopSource: 'ADMIN' as const },
  { ...BASE, productPublicId: 'prd_pending', name: '승인대기 상품', status: 'PENDING' as const },
  { ...BASE, productPublicId: 'prd_rejected', name: '반려 상품', status: 'REJECTED' as const },
]

function body() {
  return document.body
}

async function click(testId: string): Promise<void> {
  const button = body().querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)
  if (!button) throw new Error(`${testId} 없음`)
  button.click()
  await flushPromises()
}

describe('resolveSellerSaleAction (순수 함수)', () => {
  it('SALE → 판매중지 / STOPPED+SELLER → 재판매 / STOPPED+ADMIN·주체 불명 → 재판매 비활성 + 운영자 문의 / 그 외 → 액션 없음', () => {
    expect(resolveSellerSaleAction('SALE', undefined)).toEqual({ action: 'STOP', disabled: false, note: null })
    expect(resolveSellerSaleAction('STOPPED', 'SELLER')).toEqual({ action: 'RESUME', disabled: false, note: null })
    expect(resolveSellerSaleAction('STOPPED', 'ADMIN')).toEqual({ action: 'RESUME', disabled: true, note: SELLER_ADMIN_STOPPED_NOTE })
    expect(resolveSellerSaleAction('STOPPED', undefined)).toEqual({ action: 'RESUME', disabled: true, note: SELLER_ADMIN_STOPPED_NOTE })
    for (const status of ['PENDING', 'REJECTED', 'DRAFT', 'APPROVED', 'HIDDEN'] as const) {
      expect(resolveSellerSaleAction(status, undefined), status).toEqual({ action: null, disabled: false, note: null })
      expect(hasSellerSaleAction(status, undefined)).toBe(false)
    }
    expect(hasSellerSaleAction('SALE', undefined)).toBe(true)
    expect(hasSellerSaleAction('STOPPED', 'ADMIN')).toBe(true) // 비활성이라도 사유 안내를 위해 노출
  })

  it('액션 → BE status 매핑 · 확인 문구에 상품명·라벨 포함 · 422 코드 메시지', () => {
    expect(toSaleStatusTarget('STOP')).toBe('STOPPED')
    expect(toSaleStatusTarget('RESUME')).toBe('SALE')
    expect(saleActionConfirmMessage('STOP', '반찬통')).toContain('"반찬통"을(를) 판매중지')
    expect(saleActionConfirmMessage('STOP', '반찬통')).toContain('진행 중 주문은 영향 없음')
    expect(saleActionConfirmMessage('RESUME', '반찬통')).toContain('재판매')
    expect(toSellerErrorMessage(ADMIN_STOPPED_422)).toContain('운영자에게 문의')
    expect(toSellerErrorMessage(INVALID_STATE_422)).toContain('허용되지 않는 전환')
  })
})

describe('SellerProductTable 행 메뉴 · 중지 주체', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('행 메뉴는 SALE·STOPPED 3행에만 노출(PENDING·REJECTED 미노출) · STOPPED 행에 주체 라벨 · 수정 버튼은 전 행', async () => {
    await mountSuspended(SellerProductTable, {
      props: { items: PRODUCTS, totalCount: 5, page: 0, size: 20, loading: false },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    expect(body().querySelectorAll('[data-testid="row-edit"]')).toHaveLength(5)
    expect(body().querySelectorAll('[data-testid="row-menu"]')).toHaveLength(3)
    const sources = Array.from(body().querySelectorAll('[data-testid="stop-source"]')).map((node) => node.textContent?.trim())
    expect(sources).toEqual(['셀러 판매중지', '관리자 판매중지'])
  })
})

describe('SellerProductSaleStatusDialog', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    // v-dialog(VOverlay)는 window.visualViewport를 읽는데 테스트 DOM에는 없다 → 최소 stub.
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  async function mountDialog(action: 'STOP' | 'RESUME', product = PRODUCTS[0]) {
    const wrapper = await mountSuspended(SellerProductSaleStatusDialog, {
      props: { open: false, action, product },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()
    return wrapper
  }

  it('판매중지 성공 → changeSaleStatus(STOP) · success 토스트 · done', async () => {
    productsApiMock.changeSaleStatus.mockResolvedValue({})
    const wrapper = await mountDialog('STOP')
    expect(body().querySelector('[data-testid="sale-status-title"]')?.textContent?.trim()).toBe('판매중지')
    expect(body().querySelector('[data-testid="sale-status-message"]')?.textContent).toContain('"판매중 상품"을(를) 판매중지')
    await click('sale-status-ok')
    expect(productsApiMock.changeSaleStatus).toHaveBeenCalledWith('prd_sale', 'STOP')
    expect(toastMock.success).toHaveBeenCalledWith('"판매중 상품" 판매중지 처리했습니다.')
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('재판매 422 PRODUCT_STOPPED_BY_ADMIN(그사이 관리자 중지) → warning 토스트(운영자 문의) + stale · PRODUCT_INVALID_STATE → warning + stale', async () => {
    productsApiMock.changeSaleStatus.mockRejectedValueOnce(ADMIN_STOPPED_422)
    const wrapper = await mountDialog('RESUME', PRODUCTS[1])
    await click('sale-status-ok')
    expect(toastMock.warning).toHaveBeenCalledWith(expect.stringContaining('운영자에게 문의'))
    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('done')).toBeUndefined()

    productsApiMock.changeSaleStatus.mockRejectedValueOnce(INVALID_STATE_422)
    await click('sale-status-ok')
    expect(toastMock.warning).toHaveBeenLastCalledWith(expect.stringContaining('허용되지 않는 전환'))
    expect(wrapper.emitted('stale')).toHaveLength(2)
  })

  it('403 SELLER_SUSPENDED → danger 토스트 직접 표시 + cancel · 닫기 → cancel', async () => {
    productsApiMock.changeSaleStatus.mockRejectedValueOnce(SUSPENDED_403)
    const wrapper = await mountDialog('STOP')
    await click('sale-status-ok')
    expect(toastMock.danger).toHaveBeenCalledWith(expect.stringContaining('정지 상태의 셀러'))
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    await click('sale-status-close')
    expect(wrapper.emitted('cancel')).toHaveLength(2)
  })
})

describe('SellerProductSaleStatusCard', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  async function mountCard(detail: { status: 'SALE' | 'STOPPED' | 'PENDING'; saleStopSource?: 'ADMIN' | 'SELLER'; soldoutManual: boolean }) {
    return mountSuspended(SellerProductSaleStatusCard, {
      props: { detail: { productPublicId: 'prd_sale', name: '판매중 상품', ...detail } },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
  }

  it('SALE → 판매중지 버튼 활성 → saleAction(STOP) / STOPPED+ADMIN → 재판매 비활성 + 문의 안내 + 주체 라벨 / PENDING → 버튼 없음·안내만', async () => {
    const saleWrapper = await mountCard({ status: 'SALE', soldoutManual: false })
    const stopButton = body().querySelector<HTMLButtonElement>('[data-testid="sale-card-action"]')
    expect(stopButton?.textContent?.trim()).toBe('판매중지')
    expect(stopButton?.disabled).toBe(false)
    stopButton?.click()
    expect(saleWrapper.emitted('saleAction')?.[0]).toEqual(['STOP'])
    document.body.innerHTML = ''

    await mountCard({ status: 'STOPPED', saleStopSource: 'ADMIN', soldoutManual: false })
    const resumeButton = body().querySelector<HTMLButtonElement>('[data-testid="sale-card-action"]')
    expect(resumeButton?.textContent?.trim()).toBe('재판매')
    expect(resumeButton?.disabled).toBe(true)
    expect(body().querySelector('[data-testid="sale-card-note"]')?.textContent).toBe(SELLER_ADMIN_STOPPED_NOTE)
    expect(body().querySelector('[data-testid="sale-card-stop-source"]')?.textContent?.trim()).toBe('관리자 판매중지')
    document.body.innerHTML = ''

    await mountCard({ status: 'PENDING', soldoutManual: false })
    expect(body().querySelector('[data-testid="sale-card-action"]')).toBeNull()
    expect(body().querySelector('[data-testid="sale-card-note"]')?.textContent).toContain('승인·반려는 관리자가 처리')
  })

  it('품절 스위치 → changeSoldOut(true) · success 토스트 · changed / 실패 → warning 토스트 · changed(서버 값 재조회)', async () => {
    productsApiMock.changeSoldOut.mockResolvedValueOnce({})
    const wrapper = await mountCard({ status: 'SALE', soldoutManual: false })
    const toggle = body().querySelector<HTMLInputElement>('[data-testid="sale-card-soldout"] input')
    if (!toggle) throw new Error('품절 스위치 없음')
    toggle.click()
    await flushPromises()
    expect(productsApiMock.changeSoldOut).toHaveBeenCalledWith('prd_sale', true)
    expect(toastMock.success).toHaveBeenCalledWith(expect.stringContaining('수동 품절로 설정'))
    expect(wrapper.emitted('changed')).toHaveLength(1)

    // 부모가 재조회해 서버 값(true)을 내려준 뒤 해제 시도 실패 → warning + changed(스위치는 서버 값으로 복귀).
    await wrapper.setProps({ detail: { productPublicId: 'prd_sale', name: '판매중 상품', status: 'SALE', soldoutManual: true } })
    productsApiMock.changeSoldOut.mockRejectedValueOnce({ status: 404, data: { code: 'PRODUCT_NOT_FOUND' } })
    toggle.click()
    await flushPromises()
    expect(productsApiMock.changeSoldOut).toHaveBeenLastCalledWith('prd_sale', false)
    expect(toastMock.warning).toHaveBeenCalledWith(expect.stringContaining('상품을 찾을 수 없습니다'))
    expect(wrapper.emitted('changed')).toHaveLength(2)
  })
})
