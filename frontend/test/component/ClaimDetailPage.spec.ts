import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ClaimDetailPage from '~/pages/claims/[claimPublicId].vue'
import type { ClaimDetail } from '~/types/claim'

// Track 105-4g-3: 클레임 상세 최상단 대상 품목 카드(썸네일·상품명·옵션·수량·주문번호·주문 상세 보기)와 "취소·반품·교환" 제목(기준 스킨 renew).
const { useClaimDetailMock, routeMock } = vi.hoisted(() => ({
  useClaimDetailMock: vi.fn(),
  routeMock: { query: {}, params: { claimPublicId: 'clm_test' }, meta: {}, path: '/claims/clm_test' },
}))
mockNuxtImport('useClaimDetail', () => useClaimDetailMock)
mockNuxtImport('useClaim', () => () => ({ registerReturnShipment: vi.fn(), cancelClaim: vi.fn() }))
mockNuxtImport('useRoute', () => () => routeMock)

function claimDetail(overrides: Partial<ClaimDetail>): ClaimDetail {
  return {
    publicId: 'clm_test',
    orderItemPublicId: 'oit_test',
    claimType: 'CANCEL',
    status: 'REQUESTED',
    reasonCode: 'BUYER_CHANGED_MIND',
    reasonDetail: null,
    requestedAt: '2026-09-20T12:00:00+09:00',
    processedAt: null,
    rejectReasonCode: null,
    rejectMemo: null,
    refundStatus: null,
    returnShipmentRequired: false,
    attachmentUrls: [],
    ...overrides,
  }
}

function mockDetail(detail: ClaimDetail): void {
  useClaimDetailMock.mockReturnValue({ data: ref(detail), pending: ref(false), error: ref(null), refresh: vi.fn() })
}

describe('pages/claims/[claimPublicId].vue 대상 품목 카드(Track 105-4g-3)', () => {
  beforeEach(() => {
    useClaimDetailMock.mockReset()
  })

  it('item·orderNo·orderId 있음 → 썸네일·상품명·옵션·수량·주문번호 칩·주문 상세 링크(orderId)', async () => {
    mockDetail(claimDetail({
      orderId: 'ord_test',
      orderNo: '20260920-AB12CD',
      item: { productName: '하드커버 노트', optionLabel: '색상: 블랙', quantity: 2, thumbnailUrl: 'https://img.test/note.jpg' },
    }))
    const wrapper = await mountSuspended(ClaimDetailPage)
    const card = wrapper.find('[data-testid="claim-target-item"]')
    expect(card.exists()).toBe(true)
    expect(card.find('img').attributes('src')).toBe('https://img.test/note.jpg')
    expect(card.find('[data-testid="claim-target-item-name"]').text()).toBe('하드커버 노트')
    expect(card.text().replace(/\s+/g, ' ')).toContain('색상: 블랙 · 수량 2개')
    expect(card.find('[data-testid="claim-target-order-no"]').text()).toBe('20260920-AB12CD')
    expect(card.find('[data-testid="claim-target-order-link"]').attributes('href')).toBe('/orders/ord_test')
    expect(card.text()).not.toContain('ord_test')
  })

  it('썸네일·옵션 없음(삭제 상품·단순 상품) → 이미지 대기 면·수량만', async () => {
    mockDetail(claimDetail({ orderId: 'ord_test', orderNo: '20260920-AB12CD', item: { productName: '삭제상품', quantity: 1 } }))
    const wrapper = await mountSuspended(ClaimDetailPage)
    const card = wrapper.find('[data-testid="claim-target-item"]')
    expect(card.find('img').exists()).toBe(false)
    expect(card.text().replace(/\s+/g, ' ')).toContain('수량 1개')
    expect(card.text()).not.toContain(' · 수량')
  })

  it('대상 품목이 없는 옛 응답 → 카드 생략 · 제목은 "취소·반품·교환 상세"', async () => {
    mockDetail(claimDetail({}))
    const wrapper = await mountSuspended(ClaimDetailPage)
    expect(wrapper.find('[data-testid="claim-target-item"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('취소·반품·교환 상세')
    expect(wrapper.text()).not.toContain('클레임')
  })
})
