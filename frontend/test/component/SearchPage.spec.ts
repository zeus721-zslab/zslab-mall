import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import SearchPage from '~/pages/search.vue'

// 페이지는 껍데기라 route.query.keyword → ProductListView 마운트 여부·제목만 검증한다. 데이터 훅은 mock.
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

describe('pages/search.vue', () => {
  beforeEach(() => {
    useProductListMock.mockReset()
    useProductListMock.mockReturnValue(emptyListState())
    useCategoriesMock.mockReturnValue({ data: ref([]), pending: ref(false), error: ref(null), refresh: vi.fn() })
    routeMock.query = {}
  })

  it('keyword 없음 → "검색어를 입력하세요"·useProductList 미호출', async () => {
    const wrapper = await mountSuspended(SearchPage)
    expect(wrapper.text()).toContain('검색어를 입력하세요')
    expect(useProductListMock).not.toHaveBeenCalled()
  })

  it('keyword 공백만 → 안내 문구·useProductList 미호출', async () => {
    routeMock.query = { keyword: '   ' }
    const wrapper = await mountSuspended(SearchPage)
    expect(wrapper.text()).toContain('검색어를 입력하세요')
    expect(useProductListMock).not.toHaveBeenCalled()
  })

  it('keyword 있음 → 제목 \'{keyword}\' 검색 결과·useProductList에 trim 값 전달·카테고리 탭 없음', async () => {
    routeMock.query = { keyword: ' 베이직 ' }
    const wrapper = await mountSuspended(SearchPage)
    expect(wrapper.find('h1').text()).toBe("'베이직' 검색 결과")
    expect(useProductListMock).toHaveBeenCalledTimes(1)
    const keywordRef = useProductListMock.mock.calls[0]![2] as { value: string | null }
    expect(keywordRef.value).toBe('베이직')
    expect(wrapper.find('[data-testid="category-tabs"]').exists()).toBe(false)
  })
})
