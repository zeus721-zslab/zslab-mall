import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import HomeProductGrid from '~/components/HomeProductGrid.vue'
import type { ProductSummary } from '~/types/product'

// useProducts를 상태별로 갈아끼우기 위해 hoisted 홀더에 mock을 두고, 각 테스트에서 반환값을 주입한다.
// mockNuxtImport는 hoisting되므로 mock 함수는 vi.hoisted로 먼저 생성해야 참조가 성립한다.
const { useProductsMock } = vi.hoisted(() => ({ useProductsMock: vi.fn() }))
mockNuxtImport('useProducts', () => useProductsMock)

// success 상태 fixture(유효 ProductSummary 2건).
const products: ProductSummary[] = [
  {
    productPublicId: 'prd_0001',
    name: '첫째 상품',
    mainImageUrl: 'https://example.com/a.jpg',
    displayPrice: 10000,
    soldOut: false,
    categoryId: 1,
    categoryName: '의류',
    sellerName: '샵A',
  },
  {
    productPublicId: 'prd_0002',
    name: '둘째 상품',
    mainImageUrl: null,
    displayPrice: 20000,
    soldOut: false,
    categoryId: 1,
    categoryName: '의류',
    sellerName: '샵B',
  },
]

// 컴포넌트가 data/pending/error를 .value로 소비하므로 각각 ref로 감싼 useFetch 반환 형태를 흉내낸다.
function fetchState(overrides: {
  data?: unknown
  pending?: boolean
  error?: unknown
}) {
  return {
    data: ref(overrides.data ?? null),
    pending: ref(overrides.pending ?? false),
    error: ref(overrides.error ?? null),
    refresh: vi.fn(),
  }
}

describe('HomeProductGrid 4상태', () => {
  beforeEach(() => {
    useProductsMock.mockReset()
  })

  it('pending → 로딩 스켈레톤을 렌더한다', async () => {
    useProductsMock.mockReturnValue(fetchState({ pending: true, data: null, error: null }))
    const wrapper = await mountSuspended(HomeProductGrid)
    expect(wrapper.find('.animate-pulse').exists()).toBe(true)
  })

  it('error → ErrorState 기본 문구를 렌더한다', async () => {
    useProductsMock.mockReturnValue(fetchState({ pending: false, error: new Error('x') }))
    const wrapper = await mountSuspended(HomeProductGrid)
    expect(wrapper.text()).toContain('상품을 불러오지 못했습니다')
  })

  it('empty → EmptyState 기본 문구를 렌더한다', async () => {
    useProductsMock.mockReturnValue(
      fetchState({ data: { items: [], page: 1, size: 8, totalCount: 0, hasNext: false } }),
    )
    const wrapper = await mountSuspended(HomeProductGrid)
    expect(wrapper.text()).toContain('등록된 상품이 없습니다')
  })

  it('success → 상품명을 렌더한다', async () => {
    useProductsMock.mockReturnValue(
      fetchState({ data: { items: products, page: 1, size: 8, totalCount: 2, hasNext: false } }),
    )
    const wrapper = await mountSuspended(HomeProductGrid)
    expect(wrapper.text()).toContain('첫째 상품')
    expect(wrapper.text()).toContain('둘째 상품')
  })
})
