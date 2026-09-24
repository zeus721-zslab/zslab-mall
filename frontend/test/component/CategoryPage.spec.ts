import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import CategoryPage from '~/pages/categories/[id].vue'

// 페이지는 껍데기라 params.id 검증 → 목록 조회 여부만 검증한다. 데이터 훅(useProductPage)은 mock(FE-75 기준 스킨 renew).
const { useProductPageMock, routeMock } = vi.hoisted(() => ({
  useProductPageMock: vi.fn(),
  routeMock: { query: {} as Record<string, string>, params: {} as Record<string, string>, meta: {} },
}))
mockNuxtImport('useProductPage', () => useProductPageMock)
mockNuxtImport('useRoute', () => () => routeMock)

function emptyListState() {
  const items = ref([])
  return {
    items: computed(() => items.value),
    totalCount: ref(0),
    pending: ref(false),
    hasError: ref(false),
    page: ref(1),
    totalPages: ref(1),
    sort: ref('LATEST'),
    sortOptions: [],
    categories: ref([]),
    activeCategoryName: ref(null),
    setSort: vi.fn(),
    goToPage: vi.fn(),
    retry: vi.fn(),
  }
}

describe('pages/categories/[id].vue', () => {
  beforeEach(() => {
    useProductPageMock.mockReset()
    useProductPageMock.mockReturnValue(emptyListState())
    routeMock.params = {}
  })

  it.each(['abc', '0', '-1', '1.5', ''])('id=%j(양의 정수 아님) → 빈 상태·useProductPage 미호출', async (id) => {
    routeMock.params = { id }
    const wrapper = await mountSuspended(CategoryPage)
    expect(wrapper.text()).toContain('등록된 상품이 없습니다')
    expect(useProductPageMock).not.toHaveBeenCalled()
  })

  it('id=1 → 목록 렌더·categoryId 1 전달·탭 렌더', async () => {
    routeMock.params = { id: '1' }
    const wrapper = await mountSuspended(CategoryPage)
    expect(useProductPageMock).toHaveBeenCalledTimes(1)
    const categoryIdRef = useProductPageMock.mock.calls[0]![0] as { value: number | null }
    expect(categoryIdRef.value).toBe(1)
    expect(wrapper.find('[data-testid="category-tabs"]').exists()).toBe(true)
  })
})
