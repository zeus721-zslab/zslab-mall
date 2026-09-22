import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminProductTable from '#layers/admin/app/components/admin/AdminProductTable.vue'
import type { AdminProductSummary } from '#layers/admin/app/types/admin-product'

/**
 * 상품 테이블 행 메뉴 — FE-58: Vuetify VListItem은 disabled여도 click을 emit하므로 비활성 상태 전환 항목의 프로그래밍 클릭이
 * changeStatus emit(→ BE 422)으로 새지 않는지, 활성 항목은 정상 emit 하는지 검증한다.
 */
const SALE_ITEM: AdminProductSummary = {
  productPublicId: 'prd_A',
  name: '판매중 상품',
  categoryId: 1,
  stockTotal: 3,
  status: 'SALE',
  soldOut: false,
  soldOutManual: false,
  basePrice: 1000,
  createdAt: '2026-09-01T00:00:00',
}

function query<T extends HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

async function click(testId: string): Promise<void> {
  const target = query<HTMLElement>(testId)
  if (!target) throw new Error(`${testId} 없음`)
  target.click()
  await flushPromises()
}

describe('AdminProductTable — 비활성 상태 전환 항목 클릭 가드(FE-58)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('SALE 상품: 비활성 SALE 항목 클릭 → changeStatus 없음 · 활성 STOPPED 항목 클릭 → changeStatus 1회', async () => {
    const wrapper = await mountSuspended(AdminProductTable, {
      props: { items: [SALE_ITEM], totalCount: 1, page: 1, size: 20, loading: false, pendingIds: new Set<string>(), selected: [] },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    await click('row-menu')
    expect(query('row-status-SALE')?.classList.contains('v-list-item--disabled')).toBe(true)

    await click('row-status-SALE')
    expect(wrapper.emitted('changeStatus')).toBeUndefined()

    await click('row-status-STOPPED')
    expect(wrapper.emitted('changeStatus')).toEqual([[SALE_ITEM, 'STOPPED']])
    vi.unstubAllGlobals()
  })
})
