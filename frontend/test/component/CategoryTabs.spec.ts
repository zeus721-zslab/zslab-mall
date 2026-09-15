import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import CategoryTabs from '~/components/product/CategoryTabs.vue'
import type { CategorySummary } from '~/types/category'

const { useCategoriesMock } = vi.hoisted(() => ({ useCategoriesMock: vi.fn() }))
mockNuxtImport('useCategories', () => useCategoriesMock)

const categories: CategorySummary[] = [
  { categoryId: 1, displayName: '데모', sortOrder: 0 },
  { categoryId: 2, displayName: '의류', sortOrder: 1 },
]

function categoriesState(overrides: { data?: CategorySummary[] | null; error?: unknown }) {
  return { data: ref(overrides.data ?? null), pending: ref(false), error: ref(overrides.error ?? null), refresh: vi.fn() }
}

function links(wrapper: Awaited<ReturnType<typeof mountSuspended>>) {
  return wrapper.findAll('a').map((anchor) => ({ href: anchor.attributes('href'), label: anchor.text() }))
}

describe('ProductCategoryTabs', () => {
  beforeEach(() => {
    useCategoriesMock.mockReset()
  })

  it('전체 + 카테고리 N개 링크 경로(/products·/categories/[id])', async () => {
    useCategoriesMock.mockReturnValue(categoriesState({ data: categories }))
    const wrapper = await mountSuspended(CategoryTabs)
    expect(links(wrapper)).toEqual([
      { href: '/products', label: '전체' },
      { href: '/categories/1', label: '데모' },
      { href: '/categories/2', label: '의류' },
    ])
  })

  it('categoryId 일치 탭이 활성(aria-current=page), 없으면 "전체" 활성', async () => {
    useCategoriesMock.mockReturnValue(categoriesState({ data: categories }))
    const withCategory = await mountSuspended(CategoryTabs, { props: { categoryId: 2 } })
    const activeWith = withCategory.findAll('a[aria-current="page"]')
    expect(activeWith).toHaveLength(1)
    expect(activeWith[0]!.text()).toBe('의류')

    const withoutCategory = await mountSuspended(CategoryTabs)
    const activeWithout = withoutCategory.findAll('a[aria-current="page"]')
    expect(activeWithout).toHaveLength(1)
    expect(activeWithout[0]!.text()).toBe('전체')
  })

  it('조회 실패 → "전체" 탭만', async () => {
    useCategoriesMock.mockReturnValue(categoriesState({ data: null, error: new Error('x') }))
    const wrapper = await mountSuspended(CategoryTabs)
    expect(links(wrapper)).toEqual([{ href: '/products', label: '전체' }])
  })
})
