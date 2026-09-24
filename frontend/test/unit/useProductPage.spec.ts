import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h, ref, type Ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import { useProductPage } from '~/composables/useProductPage'
import type { ProductListResponse } from '~/types/product'

// useAsyncData는 Nuxt 앱 컨텍스트가 필요하므로 composable을 호출하는 최소 컴포넌트를 mountSuspended로 세운다(useProductList.spec과 같은 방식).
// 네트워크는 전역 $fetch를 stub해 상품 목록 호출 인자(경로·query)만 검증한다. 카테고리 조회는 mock으로 뺀다.
const { useCategoriesMock } = vi.hoisted(() => ({ useCategoriesMock: vi.fn() }))
mockNuxtImport('useCategories', () => useCategoriesMock)

const emptyResponse: ProductListResponse = { items: [], page: 0, size: 20, totalCount: 0, hasNext: false }
const fetchMock = vi.fn(async () => emptyResponse)

function hostComponent(categoryId: Ref<number | null>, keyword?: Ref<string>) {
  return defineComponent({
    setup() {
      useProductPage(categoryId, keyword)
      return () => h('div')
    },
  })
}

/** 상품 목록($fetch '/v1/products') 호출들의 query 객체. */
function productQueries(): Record<string, string | number>[] {
  const calls = fetchMock.mock.calls as unknown as [string, { query: Record<string, string | number> }][]
  return calls.filter(([path]) => path === '/v1/products').map(([, options]) => options.query)
}

describe('useProductPage keyword', () => {
  beforeEach(() => {
    fetchMock.mockClear()
    vi.stubGlobal('$fetch', fetchMock)
    useCategoriesMock.mockReturnValue({ data: ref([]), pending: ref(false), error: ref(null), refresh: vi.fn() })
    // 같은 key는 useAsyncData 캐시가 재사용돼 $fetch가 생략되므로 매번 비운다.
    clearNuxtData()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keyword 미지정(목록) → query에 keyword 없음', async () => {
    await mountSuspended(hostComponent(ref(null)))
    expect(productQueries()).toEqual([{ sort: 'LATEST', page: 0, size: 20 }])
  })

  it('keyword 지정(검색) → query.keyword 전달', async () => {
    await mountSuspended(hostComponent(ref(null), ref('후디')))
    expect(productQueries()).toEqual([{ sort: 'LATEST', page: 0, size: 20, keyword: '후디' }])
  })

  it('빈 검색어 → 상품 목록 조회 안 함', async () => {
    await mountSuspended(hostComponent(ref(null), ref('')))
    expect(productQueries()).toEqual([])
  })
})
