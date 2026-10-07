import { describe, it, expect, beforeEach } from 'vitest'
import { h } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import SellerDashboardListCard from '#layers/seller/app/components/seller/SellerDashboardListCard.vue'
import SellerDashboardPending from '#layers/seller/app/components/seller/SellerDashboardPending.vue'
import SellerOrderItemTable from '#layers/seller/app/components/seller/SellerOrderItemTable.vue'
import SellerDeliveryTable from '#layers/seller/app/components/seller/SellerDeliveryTable.vue'
import type { SellerDashboardPending as SellerDashboardPendingCounts } from '#layers/seller/app/types/seller-dashboard'
import SellerInboxList from '#layers/seller/app/components/seller/SellerInboxList.vue'
import type { InboxTypeCount } from '~/types/inbox'

/**
 * FE-111(퀄리티 9-3) 셀러 화면: 응답 전에는 대시보드 리스트 카드 빈 문구(D6) · 처리 대기 칩 "없음"(D7) · 주문/배송 표 하단 "0-0 / 0"(D3)이
 * 보이지 않고, 응답 후에는 그대로 보인다.
 */
const EMPTY_TEXT = '결제된 주문 품목이 없습니다'
const ZERO_PENDING: SellerDashboardPendingCounts = { deliveryReady: 0, claimRequested: 0, lowStock: 0, settlementPending: 0, longShipping: 0 }

function footerText(): string | null {
  return document.body.querySelector('.v-data-table-footer')?.textContent ?? null
}

describe('셀러 응답 대기 중 빈 상태·0건 미표시(FE-111)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('SellerDashboardListCard(D6): rows null이면 빈 문구 없음 → 빈 배열(응답 후)이면 빈 문구', async () => {
    const props = { title: '최근 주문 품목', rows: null as string[] | null, rowKey: (row: string) => row, emptyText: EMPTY_TEXT, testId: 'card' }
    const wrapper = await mountSuspended(SellerDashboardListCard, {
      props,
      slots: { row: ({ row }: { row: string }) => h('span', row) },
      global: { plugins: [createVuetify()] },
    })
    expect(wrapper.find('[data-testid="card-empty"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(EMPTY_TEXT)

    await wrapper.setProps({ rows: [] })
    expect(wrapper.get('[data-testid="card-empty"]').text()).toBe(EMPTY_TEXT)
  })

  it('SellerDashboardPending(D7): pending null이면 칩 "—"(없음 아님) → 0건 응답 후 칩 "없음"', async () => {
    const wrapper = await mountSuspended(SellerDashboardPending, {
      props: { pending: null as SellerDashboardPendingCounts | null },
      global: { plugins: [createVuetify()] },
    })
    const chipText = () => wrapper.get('[data-testid="dashboard-pending-deliveryReady"] .v-chip').text()
    expect(chipText()).toBe('—')
    expect(wrapper.text()).not.toContain('없음')

    await wrapper.setProps({ pending: ZERO_PENDING })
    expect(chipText()).toBe('없음')
  })

  it.each([
    ['SellerOrderItemTable', SellerOrderItemTable],
    ['SellerDeliveryTable', SellerDeliveryTable],
  ])('%s(D3): 첫 로딩(행 없음) 중 하단 "0-0 / 0" 없음 → 응답 후 0건이면 "0-0 / 0"', async (_name, table) => {
    const wrapper = await mountSuspended(table, {
      props: { items: [], totalCount: 0, page: 0, size: 20, loading: true, pendingIds: new Set<string>() },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    expect(footerText()).toBeNull()

    await wrapper.setProps({ loading: false })
    expect(footerText()).toContain('0-0 / 0')
    wrapper.unmount()
  })
})

describe('셀러 인박스 유형 칩 건수(UX-02)', () => {
  const baseProps = {
    items: [], counts: [] as InboxTypeCount[], tab: 'TODAY' as const, type: null, selectedKey: null,
    loading: true, error: null as string | null, truncated: false, nowMs: 0,
  }

  // 조회 실패 시 이전 건수 유지는 페이지(inbox.vue — 성공 시에만 counts 대입)가 책임진다. 컴포넌트는 counts만 본다.
  it('응답 전(counts 빈 배열)에는 건수 없이 유형명만 → 응답 후 건수 표시', async () => {
    const wrapper = await mountSuspended(SellerInboxList, { props: baseProps, global: { plugins: [createVuetify()] } })
    const allChip = () => wrapper.get('[data-testid="inbox-type-chip-ALL"]').text()
    const readyChip = () => wrapper.get('[data-testid="inbox-type-chip-DELIVERY_READY"]').text()
    expect(allChip()).toBe('전체')
    expect(readyChip()).not.toMatch(/\d/)

    const counts: InboxTypeCount[] = [{ type: 'DELIVERY_READY', count: 4 }, { type: 'LOW_STOCK', count: 1 }]
    await wrapper.setProps({ counts, loading: false })
    expect(allChip()).toBe('전체 5')
    expect(readyChip()).toMatch(/ 4$/)
  })
})
