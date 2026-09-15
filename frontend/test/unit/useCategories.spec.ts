import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import { useCategories } from '~/composables/useCategories'
import type { CategorySummary } from '~/types/category'

// 고정 key('categories')라 it 간 캐시가 공유되므로 매 it 전에 clearNuxtData로 비운다.
const fetchMock = vi.fn()

// 호출 결과를 밖으로 꺼내기 위해 setup에서 반환값을 holder에 담는다.
function mountWithCategories() {
  const holder: { result: ReturnType<typeof useCategories> | null } = { result: null }
  const component = defineComponent({
    setup() {
      holder.result = useCategories()
      return () => h('div')
    },
  })
  return { holder, mounted: mountSuspended(component) }
}

describe('useCategories', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('$fetch', fetchMock)
    clearNuxtData('categories')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('성공 → /v1/categories 호출·data에 목록', async () => {
    const categories: CategorySummary[] = [
      { categoryId: 1, displayName: '데모', sortOrder: 0 },
      { categoryId: 2, displayName: '의류', sortOrder: 1 },
    ]
    fetchMock.mockResolvedValue(categories)
    const { holder, mounted } = mountWithCategories()
    await mounted
    expect(fetchMock).toHaveBeenCalledWith('/v1/categories', expect.objectContaining({ baseURL: expect.any(String) }))
    expect(holder.result!.data.value).toEqual(categories)
    // useAsyncData 기본값은 undefined(null 아님) — 성공 시 error가 비어 있음만 확인한다.
    expect(holder.result!.error.value).toBeFalsy()
  })

  it('실패 → error 설정·data 비어 있음', async () => {
    fetchMock.mockRejectedValue(new Error('network down'))
    const { holder, mounted } = mountWithCategories()
    await mounted
    expect(holder.result!.error.value).toBeTruthy()
    expect(holder.result!.data.value).toBeFalsy()
  })
})
