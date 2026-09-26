import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RenewProductCard from '~/skins/renew/components/RenewProductCard.vue'
import RenewProductListing from '~/skins/renew/components/RenewProductListing.vue'
import type { ProductPageListVm } from '~/skins/contracts/product-page'
import type { ProductSummary } from '~/types/product'

/**
 * 성능 트랙 P2(FE-85) 상품 이미지 우선순위. priority 없음 → lazy, 있음 → eager, 'high'만 fetchpriority="high".
 * 목록은 첫 줄(5장)만 즉시 요청하고 첫 장만 높은 우선순위다.
 */
const LIST_SIZE = 7
const FIRST_ROW_COUNT = 5

function product(index: number): ProductSummary {
  return {
    productPublicId: `prd_${index}`,
    name: `상품 ${index}`,
    mainImageUrl: `/api/v1/files/products/${index}_thumb.png`,
    displayPrice: 10000,
    soldOut: false,
    categoryId: 1,
    categoryName: '기타',
    sellerName: '데모 셀러',
    sellerPublicId: 'slr_demo',
  }
}

function listVm(items: ProductSummary[]): ProductPageListVm {
  return {
    items,
    totalCount: items.length,
    pending: false,
    hasError: false,
    page: 1,
    totalPages: 1,
    sort: 'LATEST',
    sortOptions: [{ value: 'LATEST', label: '최신순' }],
    categories: [],
    activeCategoryName: null,
    setSort: async () => {},
    goToPage: async () => {},
    retry: async () => {},
  }
}

describe('RenewProductCard — 이미지 우선순위', () => {
  const CASES = [
    { priority: undefined, loading: 'lazy', fetchpriority: undefined },
    { priority: 'eager', loading: 'eager', fetchpriority: undefined },
    { priority: 'high', loading: 'eager', fetchpriority: 'high' },
  ] as const

  for (const { priority, loading, fetchpriority } of CASES) {
    it(`priority ${priority ?? '없음'} → loading ${loading} · fetchpriority ${fetchpriority ?? '없음'}`, async () => {
      const wrapper = await mountSuspended(RenewProductCard, { props: { product: product(0), priority } })
      const image = wrapper.find('img')
      expect(image.attributes('loading')).toBe(loading)
      expect(image.attributes('fetchpriority')).toBe(fetchpriority)
    })
  }
})

describe('RenewProductListing — 첫 줄 이미지 우선순위', () => {
  it('첫 카드만 fetchpriority high · 첫 줄 5장 eager · 나머지 lazy', async () => {
    const items = Array.from({ length: LIST_SIZE }, (_, index) => product(index))
    const wrapper = await mountSuspended(RenewProductListing, { props: { list: listVm(items), categoryId: null } })
    const images = wrapper.findAll('[data-testid="product-card"] img')
    expect(images).toHaveLength(LIST_SIZE)
    expect(images.map((image) => image.attributes('fetchpriority'))).toEqual(['high', ...Array(LIST_SIZE - 1).fill(undefined)])
    expect(images.map((image) => image.attributes('loading'))).toEqual([
      ...Array(FIRST_ROW_COUNT).fill('eager'),
      ...Array(LIST_SIZE - FIRST_ROW_COUNT).fill('lazy'),
    ])
  })
})
