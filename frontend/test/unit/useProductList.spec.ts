import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h, ref, type Ref } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import { useProductList } from '~/composables/useProductList'
import type { ProductListResponse, ProductSort } from '~/types/product'

// useAsyncData는 Nuxt 앱 컨텍스트가 필요하므로 composable을 호출하는 최소 컴포넌트를 mountSuspended로 세운다.
// 네트워크는 전역 $fetch를 stub해 호출 인자(경로·query)만 검증한다(응답 형태는 PagedResponse 빈 목록).
const emptyResponse: ProductListResponse = { items: [], page: 0, size: 20, totalCount: 0, hasNext: false }
const fetchMock = vi.fn(async () => emptyResponse)

function hostComponent(sort: Ref<ProductSort>, categoryId: Ref<number | null>, keyword?: Ref<string | null>) {
  return defineComponent({
    setup() {
      useProductList(sort, categoryId, keyword)
      return () => h('div')
    },
  })
}

/** 마지막 $fetch 호출의 query 객체. */
function lastQuery(): Record<string, string | number> {
  const call = fetchMock.mock.calls.at(-1) as unknown as [string, { query: Record<string, string | number> }]
  return call[1].query
}

describe('useProductList keyword', () => {
  beforeEach(() => {
    fetchMock.mockClear()
    vi.stubGlobal('$fetch', fetchMock)
    // 같은 key(sort·categoryId·keyword 조합)는 useAsyncData 캐시가 재사용돼 $fetch가 생략되므로 매번 비운다.
    clearNuxtData()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keyword 미지정 → query에 keyword 없음(sort·page·size만)', async () => {
    await mountSuspended(hostComponent(ref('LATEST'), ref(null)))
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(lastQuery()).toEqual({ sort: 'LATEST', page: 0, size: 20 })
  })

  it('keyword 빈 문자열·공백만 → keyword 파라미터 미포함', async () => {
    await mountSuspended(hostComponent(ref('LATEST'), ref(null), ref('')))
    expect(lastQuery()).not.toHaveProperty('keyword')

    fetchMock.mockClear()
    clearNuxtData()
    await mountSuspended(hostComponent(ref('LATEST'), ref(null), ref('   ')))
    expect(lastQuery()).not.toHaveProperty('keyword')
  })

  it('keyword 앞뒤 공백 → trim 값으로 포함', async () => {
    await mountSuspended(hostComponent(ref('LATEST'), ref(null), ref('  베이직  ')))
    expect(lastQuery()).toMatchObject({ keyword: '베이직' })
  })

  it('keyword + categoryId + sort 조합이 모두 query에 실린다', async () => {
    await mountSuspended(hostComponent(ref('PRICE_ASC'), ref(7), ref('후디')))
    expect(lastQuery()).toEqual({ sort: 'PRICE_ASC', page: 0, size: 20, categoryId: 7, keyword: '후디' })
  })
})
