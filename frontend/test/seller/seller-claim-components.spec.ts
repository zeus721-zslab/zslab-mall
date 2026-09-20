import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerClaimTable from '#layers/seller/app/components/seller/SellerClaimTable.vue'
import SellerClaimAttachmentImage from '#layers/seller/app/components/seller/SellerClaimAttachmentImage.vue'
import SellerOrderItemTable from '#layers/seller/app/components/seller/SellerOrderItemTable.vue'
import type { SellerClaimSummary } from '#layers/seller/app/types/seller-claim'
import type { SellerOrderItemSummary } from '#layers/seller/app/types/seller-order'

/**
 * 셀러 클레임 컴포넌트(Track 90-D-1): 표(유형 링크·상태/환불 칩·첨부 개수·처리 버튼 부재·페이지 0-base), 첨부 blob 로더(fetch Bearer → object URL →
 * 언마운트 revoke·404 플레이스홀더·늦은 응답 폐기), 확대(preview variant·부모는 attachmentId만 보관·개폐 반복 시 revoke 수 = create 수·잔존 0),
 * 품목 표 클레임 칩(openClaim). 실 네트워크 없음(fetch·store mock).
 */
const { sellerAuthMock } = vi.hoisted(() => ({ sellerAuthMock: { token: 'seller-jwt', logout: vi.fn(), markSuspended: vi.fn() } }))
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))

const CLAIMS: SellerClaimSummary[] = [
  { claimId: 'clm_1', type: 'RETURN', status: 'REQUESTED', requestedAt: '2026-09-10T10:00:00+09:00', orderNo: '20260910-A', productName: '반찬통', optionLabel: '블랙', reasonCode: 'PRODUCT_DEFECT', reasonDetail: '뚜껑 파손', attachmentCount: 2 },
  { claimId: 'clm_2', type: 'CANCEL', status: 'COMPLETED', requestedAt: '2026-09-08T09:00:00+09:00', processedAt: '2026-09-08T09:30:00+09:00', orderNo: '20260908-B', productName: '주전자', reasonCode: 'BUYER_CHANGED_MIND', refundStatus: 'COMPLETED', attachmentCount: 0 },
]

function body() {
  return document.body
}

describe('SellerClaimTable', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('유형 링크·상태 칩 의미 색·환불 칩·첨부 개수·처리 일시 대시 · 처리 버튼 0 · open 이벤트 · 페이지 0-base', async () => {
    const wrapper = await mountSuspended(SellerClaimTable, {
      props: { items: CLAIMS, totalCount: 2, page: 0, size: 20, loading: false },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    const types = Array.from(body().querySelectorAll('[data-testid="row-claim-type"]')).map((node) => node.textContent?.trim())
    expect(types).toEqual(['반품', '취소'])
    const chips = body().querySelectorAll('[data-testid="status-chip"]')
    expect(chips[0]?.textContent?.trim()).toBe('요청')
    expect(chips[0]?.className).toContain('slr-chip--warning')
    expect(chips[1]?.textContent?.trim()).toBe('완료')
    expect(chips[1]?.className).toContain('slr-chip--success')
    expect(body().querySelectorAll('[data-testid="refund-status-chip"]')).toHaveLength(1)
    expect(body().querySelector('[data-testid="refund-status-chip"]')?.textContent?.trim()).toBe('환불 완료')
    expect(body().querySelectorAll('[data-testid="row-attachment-count"]')).toHaveLength(1)
    expect(body().querySelector('[data-testid="row-attachment-count"]')?.textContent).toContain('첨부 2장')
    expect(body().querySelector('[data-testid="row-reason"]')?.textContent?.trim()).toBe('상품 불량')
    const processed = Array.from(body().querySelectorAll('[data-testid="row-processed-at"]')).map((node) => node.textContent?.trim())
    expect(processed).toEqual(['처리 —', '처리 2026.09.08 09:30'])
    // 조회 전용: 승인·거부·검수·출고 버튼이 없다
    expect(body().textContent).not.toMatch(/승인|거부|거절|검수/)
    expect(body().querySelectorAll('[data-testid="row-prepare-shipment"]')).toHaveLength(0)
    expect(body().querySelectorAll('button[data-testid="row-open"]')).toHaveLength(2)

    body().querySelector<HTMLElement>('[data-testid="row-claim-type"]')?.click()
    expect(wrapper.emitted('open')?.[0]).toEqual([CLAIMS[0]])
    wrapper.vm.$emit('update:page', 1)
    expect(wrapper.emitted('update:page')?.[0]).toEqual([1])
  })
})

describe('SellerClaimAttachmentImage(blob 로더)', () => {
  const createObjectURL = vi.fn<(blob: Blob) => string>()
  const revokeObjectURL = vi.fn<(url: string) => void>()
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    document.body.innerHTML = ''
    createObjectURL.mockReset().mockImplementation(() => `blob:mock-${createObjectURL.mock.calls.length}`)
    revokeObjectURL.mockReset()
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    Object.assign(URL, { createObjectURL, revokeObjectURL })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function okResponse(): Response {
    return { ok: true, status: 200, blob: () => Promise.resolve(new Blob(['png'])) } as unknown as Response
  }

  it('성공: Bearer 헤더로 fetch → object URL을 img src로 · 클릭 → open(페이로드 없음·URL을 부모에 넘기지 않음) · 언마운트 시 revokeObjectURL', async () => {
    fetchMock.mockResolvedValue(okResponse())
    const wrapper = await mountSuspended(SellerClaimAttachmentImage, {
      props: { url: '/api/v1/files/claims/2026/09/A.png', index: 0 },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe('/api/v1/files/claims/2026/09/A.png')
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer seller-jwt')
    expect(init?.cache).toBe('no-store')
    const img = body().querySelector<HTMLImageElement>('[data-testid="claim-attachment-thumb"] img')
    expect(img?.getAttribute('src')).toBe('blob:mock-1')
    expect(body().querySelector('[data-testid="claim-attachment-loading"]')).toBeNull()

    body().querySelector<HTMLButtonElement>('[data-testid="claim-attachment-thumb"]')?.click()
    expect(wrapper.emitted('open')?.[0]).toEqual([])

    wrapper.unmount()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-1')
  })

  it('실패 404: 플레이스홀더(열람 권한 없음 문구)·img 없음·revoke 없음 · 클릭 → 재시도 fetch', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 404 } as Response)
    await mountSuspended(SellerClaimAttachmentImage, {
      props: { url: '/api/v1/files/claims/2026/09/B.png', index: 1 },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    await flushPromises()
    const placeholder = body().querySelector<HTMLButtonElement>('[data-testid="claim-attachment-error"]')
    expect(placeholder?.textContent).toContain('열람 권한이 없거나 삭제된 사진')
    expect(body().querySelector('[data-testid="claim-attachment-thumb"]')).toBeNull()
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(revokeObjectURL).not.toHaveBeenCalled()

    placeholder?.click()
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('네트워크 예외: 일반 실패 문구 · URL 변경 시 이전 blob revoke 후 재요청', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('network'))
    const wrapper = await mountSuspended(SellerClaimAttachmentImage, {
      props: { url: '/api/v1/files/claims/2026/09/C.png', index: 0 },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    await flushPromises()
    expect(body().querySelector('[data-testid="claim-attachment-error"]')?.textContent).toContain('불러오지 못함')

    fetchMock.mockResolvedValue(okResponse())
    await wrapper.setProps({ url: '/api/v1/files/claims/2026/09/D.png' })
    await flushPromises()
    expect(body().querySelector('[data-testid="claim-attachment-thumb"] img')?.getAttribute('src')).toBe('blob:mock-1')
    await wrapper.setProps({ url: '/api/v1/files/claims/2026/09/E.png' })
    await flushPromises()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-1')
    expect(body().querySelector('[data-testid="claim-attachment-thumb"] img')?.getAttribute('src')).toBe('blob:mock-2')
  })
})

describe('SellerClaimAttachmentImage(preview variant·개폐 반복)', () => {
  const createObjectURL = vi.fn<(blob: Blob) => string>()
  const revokeObjectURL = vi.fn<(url: string) => void>()
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    document.body.innerHTML = ''
    createObjectURL.mockReset().mockImplementation(() => `blob:preview-${createObjectURL.mock.calls.length}`)
    revokeObjectURL.mockReset()
    fetchMock.mockReset().mockImplementation(() => Promise.resolve({ ok: true, status: 200, blob: () => Promise.resolve(new Blob(['png'])) } as unknown as Response))
    vi.stubGlobal('fetch', fetchMock)
    Object.assign(URL, { createObjectURL, revokeObjectURL })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('preview: 원본 img(blob src)·클릭 버튼 없음 · 확대 다이얼로그 개폐 3회 → createObjectURL 3 = revokeObjectURL 3 · 닫힌 뒤 잔존 URL 0', async () => {
    // 확대 다이얼로그를 대신하는 하네스: open이면 preview variant를 렌더, 닫으면 언마운트(부모는 URL을 들지 않는다)
    const open = ref(false)
    const Harness = defineComponent({
      setup: () => () => (open.value ? h(SellerClaimAttachmentImage, { url: '/api/v1/files/claims/2026/09/P.png', index: 0, variant: 'preview' }) : h('div')),
    })
    await mountSuspended(Harness, { global: { plugins: [createVuetify()] }, attachTo: document.body })
    for (let cycle = 1; cycle <= 3; cycle++) {
      open.value = true
      await flushPromises()
      const img = body().querySelector<HTMLImageElement>('[data-testid="claim-attachment-preview-image"] img')
      expect(img?.getAttribute('src')).toBe(`blob:preview-${cycle}`)
      expect(body().querySelector('[data-testid="claim-attachment-thumb"]')).toBeNull()
      open.value = false
      await flushPromises()
      expect(revokeObjectURL).toHaveBeenLastCalledWith(`blob:preview-${cycle}`)
    }
    expect(createObjectURL).toHaveBeenCalledTimes(3)
    expect(revokeObjectURL).toHaveBeenCalledTimes(3)
    const created = createObjectURL.mock.results.map((result) => result.value as string)
    const revoked = revokeObjectURL.mock.calls.map(([url]) => url)
    expect(created.filter((url) => !revoked.includes(url))).toEqual([]) // 잔존 object URL 없음
    expect(body().querySelector('[data-testid="claim-attachment-preview-image"]')).toBeNull()
  })
})

describe('SellerOrderItemTable 클레임 칩', () => {
  const ITEMS: SellerOrderItemSummary[] = [
    { orderItemId: 'oit_1', orderNo: '20260910-A', orderedAt: '2026-09-10T09:00:00+09:00', paidAt: '2026-09-10T09:05:00+09:00', productName: '반찬통', quantity: 1, unitPrice: 32000, totalPrice: 32000, itemStatus: 'RETURN_REQUESTED', claim: { claimId: 'clm_1', type: 'RETURN', status: 'REQUESTED', requestedAt: '2026-09-11T10:00:00+09:00' }, claimCount: 2 },
    { orderItemId: 'oit_2', orderNo: '20260909-B', orderedAt: '2026-09-09T09:00:00+09:00', paidAt: '2026-09-09T09:05:00+09:00', productName: '주전자', quantity: 1, unitPrice: 45000, totalPrice: 45000, itemStatus: 'PAID', claimCount: 0 },
  ]

  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('클레임 있는 행만 칩("반품 요청 · 2건") · 클릭 → openClaim(행) · 클레임 없는 행은 칩 없음', async () => {
    const wrapper = await mountSuspended(SellerOrderItemTable, {
      props: { items: ITEMS, totalCount: 2, page: 0, size: 20, loading: false, pendingIds: new Set<string>() },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    const chips = body().querySelectorAll<HTMLElement>('[data-testid="claim-chip"]')
    expect(chips).toHaveLength(1)
    expect(chips[0]?.textContent?.trim()).toBe('반품 요청 · 2건')
    expect(chips[0]?.className).toContain('slr-chip--warning')
    chips[0]?.click()
    expect(wrapper.emitted('openClaim')?.[0]).toEqual([ITEMS[0]])
    expect(wrapper.emitted('open')).toBeUndefined()
  })
})
