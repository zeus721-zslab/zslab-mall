import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ProductListView from '~/components/product/ProductListView.vue'
import type { ProductSummary } from '~/types/product'

// 데이터 훅(useProductList)·카테고리 훅(useCategories)을 mock해 조립(4상태·탭 표시)만 검증한다.
const { useProductListMock, useCategoriesMock } = vi.hoisted(() => ({
  useProductListMock: vi.fn(),
  useCategoriesMock: vi.fn(),
}))
mockNuxtImport('useProductList', () => useProductListMock)
mockNuxtImport('useCategories', () => useCategoriesMock)

const products: ProductSummary[] = [
  {
    productPublicId: 'prd_0001',
    name: '첫째 상품',
    mainImageUrl: 'https://example.com/a.jpg',
    displayPrice: 10000,
    soldOut: false,
    categoryId: 1,
    categoryName: '데모',
    sellerName: '샵A',
  },
]

function listState(overrides: { items?: ProductSummary[]; pending?: boolean; error?: unknown; hasNext?: boolean }) {
  const items = ref(overrides.items ?? [])
  return {
    items: computed(() => items.value),
    pending: ref(overrides.pending ?? false),
    error: ref(overrides.error ?? null),
    hasNext: ref(overrides.hasNext ?? false),
    loadMore: vi.fn(),
    reset: vi.fn(),
  }
}

const TABS = '[data-testid="category-tabs"]'

describe('ProductListView', () => {
  beforeEach(() => {
    useProductListMock.mockReset()
    useCategoriesMock.mockReset()
    useCategoriesMock.mockReturnValue({
      data: ref([{ categoryId: 1, displayName: '데모', sortOrder: 0 }]),
      pending: ref(false),
      error: ref(null),
      refresh: vi.fn(),
    })
  })

  it('pending → 로딩 스켈레톤', async () => {
    useProductListMock.mockReturnValue(listState({ pending: true }))
    const wrapper = await mountSuspended(ProductListView, { props: { title: '상품 목록' } })
    expect(wrapper.find('.animate-pulse').exists()).toBe(true)
  })

  it('error → ErrorState + 다시 시도가 reset을 호출', async () => {
    const state = listState({ error: new Error('x') })
    useProductListMock.mockReturnValue(state)
    const wrapper = await mountSuspended(ProductListView, { props: { title: '상품 목록' } })
    expect(wrapper.text()).toContain('상품을 불러오지 못했습니다')
    await wrapper.find('button').trigger('click')
    expect(state.reset).toHaveBeenCalledTimes(1)
  })

  it('empty → EmptyState', async () => {
    useProductListMock.mockReturnValue(listState({ items: [] }))
    const wrapper = await mountSuspended(ProductListView, { props: { title: '상품 목록' } })
    expect(wrapper.text()).toContain('등록된 상품이 없습니다')
  })

  it('success → 제목·상품 카드·정렬 select·카테고리 탭 렌더', async () => {
    useProductListMock.mockReturnValue(listState({ items: products }))
    const wrapper = await mountSuspended(ProductListView, { props: { title: '상품 목록' } })
    expect(wrapper.find('h1').text()).toBe('상품 목록')
    expect(wrapper.text()).toContain('첫째 상품')
    expect(wrapper.find('select[aria-label="정렬 기준"]').exists()).toBe(true)
    expect(wrapper.find(TABS).exists()).toBe(true)
  })

  it('showCategoryTabs=false → 탭 미렌더·keyword가 useProductList에 Ref로 전달', async () => {
    useProductListMock.mockReturnValue(listState({ items: products }))
    const wrapper = await mountSuspended(ProductListView, {
      props: { title: "'후디' 검색 결과", keyword: '후디', showCategoryTabs: false },
    })
    expect(wrapper.find(TABS).exists()).toBe(false)
    const [, categoryIdRef, keywordRef] = useProductListMock.mock.calls[0] as [unknown, { value: number | null }, { value: string | null }]
    expect(categoryIdRef.value).toBeNull()
    expect(keywordRef.value).toBe('후디')
  })
})
