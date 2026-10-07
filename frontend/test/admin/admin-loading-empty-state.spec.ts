import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminDashboardListCard from '#layers/admin/app/components/admin/AdminDashboardListCard.vue'
import AdminDashboardPending from '#layers/admin/app/components/admin/AdminDashboardPending.vue'
import AdminClaimTable from '#layers/admin/app/components/admin/AdminClaimTable.vue'
import AdminClaimsPage from '#layers/admin/app/pages/admin/orders/claims/index.vue'
import AdminInboxList from '#layers/admin/app/components/admin/AdminInboxList.vue'
import type { InboxTypeCount } from '~/types/inbox'
import type { AdminDashboardPending as AdminDashboardPendingCounts } from '#layers/admin/app/types/admin-dashboard'
import type { AdminClaimListQuery, AdminClaimListResponse } from '#layers/admin/app/types/admin-claim'

/**
 * FE-111(퀄리티 9-3) 관리자 화면: 응답 전에는 대시보드 리스트 카드 "데이터 없음"(D8) · 처리 대기 칩 "없음"(D9) · 클레임 목록 "처리 대기 0건"(D10) ·
 * 클레임 표 하단 "0-0 / 0"(D3)이 보이지 않고, 응답 후에는 그대로 보인다.
 */
const { claimsApiMock } = vi.hoisted(() => ({ claimsApiMock: { list: vi.fn() } }))
vi.mock('#layers/admin/app/composables/useAdminClaims', () => ({ useAdminClaims: () => claimsApiMock }))
vi.mock('#layers/admin/app/composables/useAdminOrders', () => ({ useAdminOrders: () => ({}) }))
vi.mock('#layers/admin/app/composables/useAdminToast', () => ({
  useAdminToast: () => ({ success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() }),
}))
// 실제 라우터의 페이지 미들웨어(admin·vuetify)가 로그인으로 돌리지 않게 관리자 세션을 확인된 상태로 둔다(Vuetify는 테스트 플러그인으로 설치).
vi.mock('#layers/admin/app/stores/adminAuth', () => ({
  useAdminAuthStore: () => ({ ensureSession: async () => {}, isAuthenticated: true, role: 'ADMIN', clearSession: () => {} }),
}))
vi.mock('#layers/admin/app/lib/vuetify', () => ({ ensureVuetify: async () => {} }))
mockNuxtImport('definePageMeta', () => () => {})

const ZERO_PENDING: AdminDashboardPendingCounts = {
  settlementPending: 0,
  claimRequested: 0,
  deliveryReady: 0,
  lowStock: 0,
  productPending: 0,
  sellerPending: 0,
  claimFollowup: 0,
  longShipping: 0,
  reconciliationOpen: 0,
  inquiryUnanswered: 0,
}

const EmptyStub = defineComponent({ setup: () => () => h('div') })

function deferred(): { promise: Promise<AdminClaimListResponse>; resolve: (value: AdminClaimListResponse) => void } {
  let resolve: (value: AdminClaimListResponse) => void = () => {}
  const promise = new Promise<AdminClaimListResponse>((settle) => { resolve = settle })
  return { promise, resolve }
}

describe('관리자 응답 대기 중 빈 상태·0건 미표시(FE-111)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    claimsApiMock.list.mockReset()
  })

  it('AdminDashboardListCard(D8): rows null이면 "데이터 없음" 없음 → 빈 배열(응답 후)이면 "데이터 없음"', async () => {
    const props = { title: '최근 주문', allLink: '/admin/orders', rows: null as string[] | null, rowKey: (row: string) => row, testId: 'card' }
    const wrapper = await mountSuspended(AdminDashboardListCard, {
      props,
      slots: { row: ({ row }: { row: string }) => h('span', row) },
      global: { plugins: [createVuetify()] },
    })
    expect(wrapper.find('[data-testid="card-empty"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('데이터 없음')

    await wrapper.setProps({ rows: [] })
    expect(wrapper.get('[data-testid="card-empty"]').text()).toBe('데이터 없음')
  })

  it('AdminDashboardPending(D9): pending null이면 칩 "—"(없음 아님) → 0건 응답 후 칩 "없음"', async () => {
    const wrapper = await mountSuspended(AdminDashboardPending, {
      props: { pending: null as AdminDashboardPendingCounts | null },
      global: { plugins: [createVuetify()] },
    })
    const chipText = () => wrapper.get('[data-testid="dashboard-pending-claimRequested"] .v-chip').text()
    expect(chipText()).toBe('—')
    expect(wrapper.text()).not.toContain('없음')

    await wrapper.setProps({ pending: ZERO_PENDING })
    expect(chipText()).toBe('없음')
  })

  it('AdminClaimTable(D3): 첫 로딩(행 없음) 중 하단 "0-0 / 0" 없음 → 응답 후 0건이면 "0-0 / 0"', async () => {
    const wrapper = await mountSuspended(AdminClaimTable, {
      props: { items: [], totalCount: 0, page: 0, size: 20, loading: true, pendingIds: new Set<string>() },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    const footerText = () => document.body.querySelector('.v-data-table-footer')?.textContent ?? null
    expect(footerText()).toBeNull()

    await wrapper.setProps({ loading: false })
    expect(footerText()).toContain('0-0 / 0')
    wrapper.unmount()
  })

  it('클레임 목록 페이지(D10): 응답 전 "처리 대기 —" → 0건 응답 후 "처리 대기 0건"', async () => {
    const pending = deferred()
    claimsApiMock.list.mockReturnValue(pending.promise)
    const wrapper = await mountSuspended(AdminClaimsPage, {
      route: '/admin/orders/claims',
      global: {
        plugins: [createVuetify()],
        stubs: {
          AdminClaimFilterCard: EmptyStub,
          AdminClaimTable: EmptyStub,
          AdminConfirmDialog: EmptyStub,
          AdminClaimRejectDialog: EmptyStub,
          AdminRefundInitiateDialog: EmptyStub,
          AdminClaimInspectDialog: EmptyStub,
          AdminReturnShipmentDialog: EmptyStub,
          AdminExchangeShipmentDialog: EmptyStub,
        },
      },
    })
    const chip = () => wrapper.get('[data-testid="claim-pending-chip"]').text()
    expect(chip()).toBe('처리 대기 —')
    expect(chip()).not.toContain('0건')

    pending.resolve({ items: [], page: 0, size: 20, totalCount: 0, hasNext: false, pendingCount: 0 })
    await flushPromises()
    expect(chip()).toBe('처리 대기 0건')
  })
})

describe('관리자 클레임 목록 처리 대기 건수 초기화(UX-04)', () => {
  const CLAIMS_PAGE_STUBS = {
    AdminClaimFilterCard: EmptyStub,
    AdminClaimTable: EmptyStub,
    AdminConfirmDialog: EmptyStub,
    AdminClaimRejectDialog: EmptyStub,
    AdminRefundInitiateDialog: EmptyStub,
    AdminClaimInspectDialog: EmptyStub,
    AdminReturnShipmentDialog: EmptyStub,
    AdminExchangeShipmentDialog: EmptyStub,
  }

  function claimResponse(pendingCount: number): AdminClaimListResponse {
    return { items: [], page: 0, size: 20, totalCount: 0, hasNext: false, pendingCount }
  }

  async function mountClaimsPage(route: string) {
    const wrapper = await mountSuspended(AdminClaimsPage, { route, global: { plugins: [createVuetify()], stubs: CLAIMS_PAGE_STUBS } })
    await flushPromises()
    return { chip: () => wrapper.get('[data-testid="claim-pending-chip"]').text() }
  }

  beforeEach(() => {
    claimsApiMock.list.mockReset()
  })

  // 마운트 시 route 반영으로 조회가 여러 번 일어날 수 있어 호출 순서 대신 조회 조건으로 응답을 고른다.
  it('탭(유형) 변경 → 새 응답 전까지 이전 탭 건수 대신 "—"', async () => {
    const next = deferred()
    claimsApiMock.list.mockImplementation((query: AdminClaimListQuery) =>
      query.type === 'CANCEL' ? next.promise : Promise.resolve(claimResponse(2)))
    const { chip } = await mountClaimsPage('/admin/orders/claims')
    expect(chip()).toBe('처리 대기 2건')

    await useRouter().push('/admin/orders/claims?type=CANCEL')
    await vi.waitFor(() => expect(claimsApiMock.list).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'CANCEL' })))
    expect(chip()).toBe('취소 처리 대기 —')

    next.resolve(claimResponse(1))
    await flushPromises()
    expect(chip()).toBe('취소 처리 대기 1건')
  })

  it('같은 탭에서 필터·페이지 변경 → 새 응답 전에도 건수 유지("—" 깜빡임 없음)', async () => {
    const unresolved = deferred()
    claimsApiMock.list.mockImplementation((query: AdminClaimListQuery) =>
      query.status === null && query.page === 0 ? Promise.resolve(claimResponse(2)) : unresolved.promise)
    const { chip } = await mountClaimsPage('/admin/orders/claims?type=CANCEL')
    expect(chip()).toBe('취소 처리 대기 2건')

    await useRouter().push('/admin/orders/claims?type=CANCEL&status=REQUESTED')
    await vi.waitFor(() => expect(claimsApiMock.list).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'REQUESTED', page: 0 })))
    expect(chip()).toBe('취소 처리 대기 2건')

    await useRouter().push('/admin/orders/claims?type=CANCEL&status=REQUESTED&page=1')
    await vi.waitFor(() => expect(claimsApiMock.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })))
    expect(chip()).toBe('취소 처리 대기 2건')
  })
})

describe('관리자 인박스 유형 칩 건수(UX-02)', () => {
  const baseProps = {
    items: [], counts: [] as InboxTypeCount[], tab: 'TODAY' as const, type: null, selectedKey: null, bulkSelected: [],
    loading: true, error: null as string | null, truncated: false, nowMs: 0,
  }

  // 조회 실패 시 이전 건수 유지는 페이지(inbox.vue — 성공 시에만 counts 대입)가 책임진다. 컴포넌트는 counts만 본다.
  it('응답 전(counts 빈 배열)에는 건수 없이 유형명만 → 응답 후 건수 표시', async () => {
    const wrapper = await mountSuspended(AdminInboxList, { props: baseProps, global: { plugins: [createVuetify()] } })
    const allChip = () => wrapper.get('[data-testid="inbox-type-chip-ALL"]').text()
    const claimChip = () => wrapper.get('[data-testid="inbox-type-chip-CLAIM_REQUESTED"]').text()
    expect(allChip()).toBe('전체')
    expect(claimChip()).not.toMatch(/\d/)

    const counts: InboxTypeCount[] = [{ type: 'CLAIM_REQUESTED', count: 2 }, { type: 'SELLER_DELAY', count: 1 }]
    await wrapper.setProps({ counts, loading: false })
    expect(allChip()).toBe('전체 3')
    expect(claimChip()).toMatch(/ 2$/)
  })
})
