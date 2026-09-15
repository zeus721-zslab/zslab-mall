import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import CategoryPage from '~/pages/categories/[id].vue'

// 페이지는 껍데기라 params.id 검증 → ProductListView 마운트 여부만 검증한다. 데이터 훅은 mock.
const { useProductListMock, useCategoriesMock, routeMock } = vi.hoisted(() => ({
  useProductListMock: vi.fn(),
  useCategoriesMock: vi.fn(),
  routeMock: { query: {} as Record<string, string>, params: {} as Record<string, string>, meta: {} },
}))
mockNuxtImport('useProductList', () => useProductListMock)
mockNuxtImport('useCategories', () => useCategoriesMock)
mockNuxtImport('useRoute', () => () => routeMock)

function emptyListState() {
  const items = ref([])
  return {
    items: computed(() => items.value),
    pending: ref(false),
    error: ref(null),
    hasNext: ref(false),
    loadMore: vi.fn(),
    reset: vi.fn(),
  }
}

describe('pages/categories/[id].vue', () => {
  beforeEach(() => {
    useProductListMock.mockReset()
    useProductListMock.mockReturnValue(emptyListState())
    useCategoriesMock.mockReturnValue({ data: ref([]), pending: ref(false), error: ref(null), refresh: vi.fn() })
    routeMock.params = {}
  })

  it.each(['abc', '0', '-1', '1.5', ''])('id=%j(양의 정수 아님) → 빈 상태·useProductList 미호출', async (id) => {
    routeMock.params = { id }
    const wrapper = await mountSuspended(CategoryPage)
    expect(wrapper.text()).toContain('등록된 상품이 없습니다')
    expect(useProductListMock).not.toHaveBeenCalled()
  })

  it('id=1 → ProductListView 마운트·categoryId 1 전달·탭 렌더', async () => {
    routeMock.params = { id: '1' }
    const wrapper = await mountSuspended(CategoryPage)
    expect(useProductListMock).toHaveBeenCalledTimes(1)
    const categoryIdRef = useProductListMock.mock.calls[0]![1] as { value: number | null }
    expect(categoryIdRef.value).toBe(1)
    expect(wrapper.find('[data-testid="category-tabs"]').exists()).toBe(true)
  })
})
