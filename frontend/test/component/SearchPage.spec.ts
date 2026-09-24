import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import SearchPage from '~/pages/search.vue'

// 페이지는 껍데기라 route.query.keyword → 검색어 전달·제목만 검증한다. 데이터 훅(useProductPage)은 mock.
// FE-75: 기준 스킨 renew는 productList를 선언하므로 페이지가 항상 useProductPage에 검색어를 넘긴다 — 빈 검색어의 조회 생략은 useProductPage.spec이 검증한다.
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

function keywordPassed(): string {
  return (useProductPageMock.mock.calls[0]![1] as { value: string }).value
}

describe('pages/search.vue', () => {
  beforeEach(() => {
    useProductPageMock.mockReset()
    useProductPageMock.mockReturnValue(emptyListState())
    routeMock.query = {}
  })

  it('keyword 없음 → "검색어를 입력하세요"·빈 검색어 전달', async () => {
    const wrapper = await mountSuspended(SearchPage)
    expect(wrapper.text()).toContain('검색어를 입력하세요')
    expect(keywordPassed()).toBe('')
  })

  it('keyword 공백만 → 안내 문구·빈 검색어 전달', async () => {
    routeMock.query = { keyword: '   ' }
    const wrapper = await mountSuspended(SearchPage)
    expect(wrapper.text()).toContain('검색어를 입력하세요')
    expect(keywordPassed()).toBe('')
  })

  it('keyword 있음 → 제목 \'{keyword}\' 검색 결과·useProductPage에 trim 값 전달·카테고리 탭 없음', async () => {
    routeMock.query = { keyword: ' 베이직 ' }
    const wrapper = await mountSuspended(SearchPage)
    expect(wrapper.find('h1').text()).toBe("'베이직' 검색 결과")
    expect(useProductPageMock).toHaveBeenCalledTimes(1)
    expect(keywordPassed()).toBe('베이직')
    expect(wrapper.find('[data-testid="category-tabs"]').exists()).toBe(false)
  })
})
